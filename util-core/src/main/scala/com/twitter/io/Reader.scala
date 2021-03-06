package com.twitter.io

import com.twitter.concurrent.AsyncStream
import com.twitter.util._
import java.io.{File, FileInputStream, FileNotFoundException, InputStream}
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

/**
 * A Reader represents a stream of `A`s.
 *
 * Readers permit at most one outstanding read.
 *
 * @tparam A the type of objects produced by this reader
 */
trait Reader[+A] {

  /**
   * Asynchronously read at most `n` bytes from the byte stream. The
   * returned future represents the results of the read request. If
   * the read fails, the Reader is considered failed -- future reads
   * will also fail.
   *
   * A result of None indicates EOF.
   */
  def read(n: Int): Future[Option[A]]

  /**
   * Discard this reader: its output is no longer required.
   */
  def discard(): Unit
}

object Reader {

  val Null: Reader[Nothing] = new Reader[Nothing] {
    def read(n: Int): Future[Option[Nothing]] = Future.None
    def discard(): Unit = ()
  }

  def empty[A]: Reader[A] = Null.asInstanceOf[Reader[A]]

  // see Reader.chunked
  private final class ChunkedFramer(chunkSize: Int) extends (Buf => Seq[Buf]) {
    require(chunkSize > 0, s"chunkSize should be > 0 but was $chunkSize")

    @tailrec
    private def loop(acc: Seq[Buf], in: Buf): Seq[Buf] = {
      if (in.length < chunkSize) acc :+ in
      else {
        loop(
          acc :+ in.slice(0, chunkSize),
          in.slice(chunkSize, in.length)
        )
      }
    }

    def apply(in: Buf): Seq[Buf] = {
      loop(ListBuffer(), in)
    }
  }

  // see Reader.framed
  private final class Framed(r: Reader[Buf], framer: Buf => Seq[Buf])
      extends Reader[Buf]
      with (Option[Buf] => Future[Option[Buf]]) {

    private[this] var frames: Seq[Buf] = Seq.empty

    // we only enter here when `frames` is empty.
    def apply(in: Option[Buf]): Future[Option[Buf]] = synchronized {
      in match {
        case Some(data) =>
          frames = framer(data)
          read(Int.MaxValue)
        case None =>
          Future.None
      }
    }

    def read(n: Int): Future[Option[Buf]] = synchronized {
      frames match {
        case nextFrame :: rst =>
          frames = rst
          Future.value(Some(nextFrame))
        case _ =>
          // flatMap to `this` to prevent allocating
          r.read(Int.MaxValue).flatMap(this)
      }
    }

    def discard(): Unit = synchronized {
      frames = Seq.empty
      r.discard()
    }
  }

  /**
   * Read the entire bytestream presented by `r`.
   */
  def readAll(r: Reader[Buf]): Future[Buf] = {
    def loop(left: Buf): Future[Buf] =
      r.read(Int.MaxValue).flatMap {
        case Some(right) => loop(left concat right)
        case _ => Future.value(left)
      }

    loop(Buf.Empty)
  }

  /**
   * Chunk the output of a given [[Reader]] by at most `chunkSize` (bytes). This consumes the
   * reader.
   *
   * @note The `n` (number of bytes to read) argument on the returned reader is ignored
   *       (`Int.MaxValue` is used instead).
   */
  def chunked(r: Reader[Buf], chunkSize: Int): Reader[Buf] =
    new Framed(r, new ChunkedFramer(chunkSize))

  /**
   * Reader from a Buf.
   */
  def fromBuf(buf: Buf): Reader[Buf] = BufReader(buf)

  class ReaderDiscarded extends Exception("This writer's reader has been discarded")

  /**
   * A [[Reader]] that is linked with a [[Writer]] and `close`-ing
   * is synchronous.
   *
   * Just as with [[Reader readers]] and [[Writer writers]],
   * only one outstanding `read` or `write` is permitted.
   *
   * For a proper `close`, it should only be done when
   * no writes are outstanding:
   * {{{
   *   val rw = Reader.writable()
   *   ...
   *   rw.write(buf).before(rw.close())
   * }}}
   *
   * If a producer is interested in knowing when all writes
   * have been read and the reader has seen the EOF, it can
   * wait until the future returned by `close()` is satisfied:
   * {{{
   *   val rw = Reader.writable()
   *   ...
   *   rw.close().ensure {
   *     println("party on! ♪┏(・o･)┛♪ the Reader has seen the EOF")
   *   }
   * }}}
   */
  @deprecated("Use Pipe[A] instead", "2018-8-7")
  type Writable[A <: Buf] = Pipe[A]

  /**
   * Create a new [[Writable]] which is a [[Reader]] that is linked
   * with a [[Writer]].
   *
   * @see Readers.writable() for a Java API.
   */
  @deprecated("Use Pipe() instead", "2018-8-7")
  def writable(): Pipe[Buf] = new Pipe[Buf]

  /**
   * Create a new [[Reader]] for a `File`.
   *
   * The resources held by the returned [[Reader]] are released
   * on reading of EOF and [[Reader.discard()]].
   *
   * @see `Readers.fromFile` for a Java API
   */
  @throws(classOf[FileNotFoundException])
  @throws(classOf[SecurityException])
  def fromFile(f: File): Reader[Buf] =
    fromStream(new FileInputStream(f))

  /**
   * Wrap `InputStream` with a [[Reader]].
   *
   * Note that the given `InputStream` will be closed
   * on reading of EOF and [[Reader.discard()]].
   *
   * @see `Readers.fromStream` for a Java API
   */
  def fromStream(s: InputStream): Reader[Buf] =
    InputStreamReader(s)

  /**
   * Allow [[AsyncStream]] to be consumed as a [[Reader]]
   */
  def fromAsyncStream[A <: Buf](as: AsyncStream[A]): Reader[A] = {
    val pipe = new Pipe[A]()
    // orphan the Future but allow it to clean up
    // the Pipe IF the stream ever finishes or fails
    as.foreachF(pipe.write).respond {
      case Return(_) => pipe.close()
      case Throw(e) => pipe.fail(e)
    }
    pipe
  }

  /**
   * Convenient abstraction to read from a stream of Readers as if it were a
   * single Reader.
   */
  def concat(readers: AsyncStream[Reader[Buf]]): Reader[Buf] = {
    val target = new Pipe[Buf]()
    val f = copyMany(readers, target).respond {
      case Throw(exc) => target.fail(exc)
      case _ => target.close()
    }
    new Reader[Buf] {
      def read(n: Int): Future[Option[Buf]] = target.read(n)
      def discard(): Unit = {
        // We have to do this so that when the the target is discarded we can
        // interrupt the read operation. Consider the following:
        //
        //     r.read(..) { case Some(b) => target.write(b) }
        //
        // The computation r.read(..) will be interupted because we set an
        // interrupt handler in Reader.copy to discard `r`.
        f.raise(new Reader.ReaderDiscarded())
        target.discard()
      }
    }
  }

  /**
   * Copy bytes from many Readers to a Writer. The Writer is unmanaged, the
   * caller is responsible for finalization and error handling, e.g.:
   *
   * {{{
   * Reader.copyMany(readers, writer) ensure writer.close()
   * }}}
   *
   * @param bufsize The number of bytes to read each time.
   */
  def copyMany(readers: AsyncStream[Reader[Buf]], target: Writer[Buf], bufsize: Int): Future[Unit] =
    readers.foreachF(Reader.copy(_, target, bufsize))

  /**
   * Copy bytes from many Readers to a Writer. The Writer is unmanaged, the
   * caller is responsible for finalization and error handling, e.g.:
   *
   * {{{
   * Reader.copyMany(readers, writer) ensure writer.close()
   * }}}
   */
  def copyMany(readers: AsyncStream[Reader[Buf]], target: Writer[Buf]): Future[Unit] =
    copyMany(readers, target, Writer.BufferSize)

  /**
   * Copy the bytes from a Reader to a Writer in chunks of size `n`. The Writer
   * is unmanaged, the caller is responsible for finalization and error
   * handling, e.g.:
   *
   * {{{
   * Reader.copy(r, w, n) ensure w.close()
   * }}}
   *
   * @param n The number of bytes to read on each refill of the Writer.
   */
  def copy(r: Reader[Buf], w: Writer[Buf], n: Int): Future[Unit] = {
    def loop(): Future[Unit] =
      r.read(n).flatMap {
        case None => Future.Done
        case Some(buf) => w.write(buf) before loop()
      }
    val p = new Promise[Unit]
    // We have to do this because discarding the writer doesn't interrupt read
    // operations, it only fails the next write operation.
    loop().proxyTo(p)
    p.setInterruptHandler { case exc => r.discard() }
    p
  }

  /**
   * Copy the bytes from a Reader to a Writer in chunks of size
   * `Writer.BufferSize`. The Writer is unmanaged, the caller is responsible
   * for finalization and error handling, e.g.:
   *
   * {{{
   * Reader.copy(r, w) ensure w.close()
   * }}}
   */
  def copy(r: Reader[Buf], w: Writer[Buf]): Future[Unit] = copy(r, w, Writer.BufferSize)

  /**
   * Wraps a [[ Reader[Buf] ]] and emits frames as decided by `framer`.
   *
   * @note The returned `Reader` may not be thread safe depending on the behavior
   *       of the framer.
   */
  def framed(r: Reader[Buf], framer: Buf => Seq[Buf]): Reader[Buf] = new Framed(r, framer)
}
