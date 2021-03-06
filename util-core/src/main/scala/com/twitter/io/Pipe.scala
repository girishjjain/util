package com.twitter.io

import com.twitter.util.{Future, Promise, Return, Time}

/**
 * A synchronous in-memory pipe that connects [[Reader]] and [[Writer]] in the sense
 * that a reader's input is the output of a writer.
 *
 * A pipe is structured as a smash of both interfaces, a [[Reader]] and a [[Writer]] such that can
 * be passed directly to a consumer or a producer.
 *
 * {{{
 *   def consumer(r: Reader[Buf]): Future[Unit] = ???
 *   def producer(w: Writer[Buf]): Future[Unit] = ???
 *
 *   val p = new Pipe[Buf]
 *
 *   consumer(p)
 *   producer(p)
 * }}}
 *
 * Reads and writes on the pipe are matched one to one and only one outstanding `read`
 * or `write` is permitted in the current implementation (multiple pending writes or reads
 * resolve into [[IllegalStateException]] while leaving the pipe healthy). That is, the `write`
 * (its returned [[Future]]) is resolved when the `read` consumes the written data.
 *
 * Here is, for example, a very typical write-loop that writes into a pipe-backed [[Writer]]:
 *
 * {{{
 *   def writeLoop(w: Writer[Buf], data: List[Buf]): Future[Unit] = data match {
 *     case h :: t => p.write(h).before(writeLoop(w, t))
 *     case Nil => w.close()
 *   }
 * }}}
 *
 * Reading from a pipe-backed [[Reader]] is no different from working with any other reader:
 *
 *{{{
 *   def readLoop(r: Reader[Buf], process: Buf => Future[Unit]): Future[Unit] = r.read().flatMap {
 *     case Some(chunk) => process(chunk).before(readLoop(r, process))
 *     case None => Future.Done
 *   }
 * }}}
 *
 * == Thread Safety ==
 *
 * It is safe to call `read`, `write`, `fail`, `discard`, and `close` concurrently. The individual
 * calls are synchronized on the given [[Pipe]].
 *
 * == Closing or Failing Pipes ==
 *
 * Besides expecting a write or a read, a pipe can be closed or failed. A writer can do both `close`
 * and `fail` the pipe, while reader can only fail the pipe via `discard`.
 *
 * The following behavior is expected with regards to reading from or writing into a closed or a
 * failed pipe:
 *
 *  - Writing into a closed pipe yields [[IllegalStateException]]
 *  - Reading from a closed pipe yields EOF ([[Future.None]])
 *  - Reading from or writing into a failed pipe yields a failure it was failed with
 *
 * It's also worth discussing how pipes are being closed. As closure is always initiated by a
 * producer (writer), there is a machinery allowing it to be notified when said closure is observed
 * by a consumer (reader).
 *
 * The following rules should help reasoning about closure signals in pipes:
 *
 * - Closing a pipe with a pending read resolves said read into EOF and returns a [[Future.Unit]]
 * - Closing a pipe with a pending write fails said write with [[IllegalStateException]] and
 *   returns a future that will be satisfied when a consumer observes the closure (EOF) via read
 * - Closing an idle pipe returns a future that will be satisfied when a consumer observes the
 *   closure (EOF) via read
 */
final class Pipe[A <: Buf] extends Reader[A] with Writer[A] {

  import Pipe._

  // thread-safety provided by synchronization on `this`
  private[this] var state: State[A] = State.Idle

  def read(n: Int): Future[Option[A]] = synchronized {
    state match {
      case State.Failed(exc) =>
        Future.exception(exc)

      case State.Closing(reof) =>
        state = State.Closed
        reof.setDone()
        Future.None

      case State.Closed =>
        Future.None

      case State.Idle =>
        val p = new Promise[Option[A]]
        state = State.Reading(n, p)
        p

      case State.Writing(buf, p) if buf.length <= n =>
        // pending write can fully fit into this requested read
        state = State.Idle
        p.setDone()
        Future.value(Some(buf))

      case State.Writing(buf, p) =>
        // pending write is larger than the requested read
        state = State.Writing(buf.slice(n, buf.length).asInstanceOf[A], p)
        Future.value(Some(buf.slice(0, n).asInstanceOf[A]))

      case State.Reading(_, _) =>
        Future.exception(new IllegalStateException("read() while read is pending"))
    }
  }

  def discard(): Unit = fail(new Reader.ReaderDiscarded())

  def write(buf: A): Future[Unit] = synchronized {
    state match {
      case State.Failed(exc) =>
        Future.exception(exc)

      case State.Closed | State.Closing(_) =>
        Future.exception(new IllegalStateException("write() while closed"))

      case State.Idle =>
        val p = new Promise[Unit]()
        state = State.Writing(buf, p)
        p

      case State.Reading(n, p) if n < buf.length =>
        // pending reader doesn't have enough space for this write
        val nextp = new Promise[Unit]()
        state = State.Writing(buf.slice(n, buf.length).asInstanceOf[A], nextp)
        p.setValue(Some(buf.slice(0, n).asInstanceOf[A]))
        nextp

      case State.Reading(n, p) =>
        // pending reader has enough space for the full write
        state = State.Idle
        p.setValue(Some(buf))
        Future.Done

      case State.Writing(_, _) =>
        Future.exception(new IllegalStateException("write() while write is pending"))
    }
  }

  def fail(cause: Throwable): Unit = synchronized {
    state match {
      case State.Closed | State.Failed(_) =>
      // do not update state to failing
      case State.Idle =>
        state = State.Failed(cause)
      case State.Closing(reof) =>
        state = State.Failed(cause)
        reof.setException(cause)
      case State.Reading(_, p) =>
        state = State.Failed(cause)
        p.setException(cause)
      case State.Writing(_, p) =>
        state = State.Failed(cause)
        p.setException(cause)
    }
  }

  def close(deadline: Time): Future[Unit] = synchronized {
    state match {
      case State.Failed(t) =>
        Future.exception(t)

      case State.Closed =>
        Future.Done

      case State.Closing(p) =>
        p

      case State.Idle =>
        val reof = new Promise[Unit]()
        state = State.Closing(reof)
        reof

      case State.Reading(_, p) =>
        state = State.Closed
        p.update(Return.None)
        Future.Done

      case State.Writing(_, p) =>
        val reof = new Promise[Unit]()
        state = State.Closing(reof)
        p.setException(new IllegalStateException("close() while write is pending"))
        reof
    }
  }

  override def toString: String = synchronized(s"Pipe(state=$state)")
}

object Pipe {

  private sealed trait State[+A <: Buf]

  private object State {

    /** Indicates no reads or writes are pending, and is not closed. */
    case object Idle extends State[Nothing]

    /**
     * Indicates a read is pending and is awaiting a `write`.
     *
     * @param n number of bytes to read.
     * @param p when satisfied it indicates that this read has completed.
     */
    final case class Reading[A <: Buf](n: Int, p: Promise[Option[A]]) extends State[A]

    /**
     * Indicates a write of `buf` is pending to be `read`.
     *
     * @param buf the [[Buf]] to write.
     * @param p when satisfied it indicates that this write has been fully read.
     */
    final case class Writing[A <: Buf](buf: A, p: Promise[Unit]) extends State[A]

    /** Indicates the pipe was failed. */
    final case class Failed(exc: Throwable) extends State[Nothing]

    /**
     * Indicates a close occurred while `Idle` — no reads or writes were pending.
     *
     * @param reof satisfied when a `read` sees the EOF.
     */
    final case class Closing(reof: Promise[Unit]) extends State[Nothing]

    /** Indicates the reader has seen the EOF. No more reads or writes are allowed. */
    case object Closed extends State[Nothing]
  }
}
