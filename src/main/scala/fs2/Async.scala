package fs2

import Async.Future

trait Async[F[_]] extends Monad[F] { self =>
  type Ref[A]

  /** Create a asynchronous, concurrent mutable reference. */
  def ref[A]: F[Ref[A]]

  /** The read-only portion of a `Ref`. */
  def read[A](r: Ref[A]): Future[F,A] = new Future[F,A] {
    def get = self.get(r)
    def cancellableGet = self.cancellableGet(r)
  }

  /**
   * After the returned `F[Unit]` is bound, the task is
   * running in the background. Multiple tasks may be added to a
   * `Ref[A]`.
   *
   * Satisfies: `set(v)(t) flatMap { _ => get(v) } == t`.
   */
  def set[A](q: Ref[A])(a: F[A]): F[Unit]
  def setFree[A](q: Ref[A])(a: Free[F,A]): F[Unit]

  /**
   * Obtain the value of the `Ref`, or wait until it has been `set`.
   */
  def get[A](r: Ref[A]): F[A]

  /**
   * Like `get`, but returns an `F[Unit]` that can be used cancel the subscription.
   */
  def cancellableGet[A](r: Ref[A]): F[(F[A], F[Unit])]

  /**
   * Chooses nondeterministically between `a map (Left(_))` and
   * `a2 map (Right(_))`. Result must be equivalent to one of
   * these two expressions. */
  def race[A,B](a: F[A], a2: F[B]): F[Either[A,B]] =
    bind(ref[Either[A,B]]) { ref =>
    bind(set(ref)(map(a)(Left(_)))) { _ =>
    bind(set(ref)(map(a2)(Right(_)))) { _ =>
    get(ref)
    }}}
}

object Async {

  implicit class Syntax[F[_],A](a: F[A]) {
    def race[B](b: F[B])(implicit F: Async[F]): F[Either[A,B]] = F.race(a,b)
  }

  trait Future[F[_],A] { self =>
    def get: F[A]
    def cancellableGet: F[(F[A], F[Unit])]
    def force: Pull[F,Nothing,A] = Pull.eval(get)
    def map[B](f: A => B)(implicit F: Async[F]): Future[F,B] = new Future[F,B] {
      def get = F.map(self.get)(f)
      def cancellableGet = F.map(self.cancellableGet) { case (a,cancelA) => (F.map(a)(f), cancelA) }
    }

    def race[B](b: Future[F,B])(implicit F: Async[F]): Pull[F, Nothing, Either[A,B]] =
    Pull.eval[F, Either[A,B]] {
      F.bind(F.ref[Either[A,B]]) { ref =>
      F.bind(self.cancellableGet) { case (a, cancelA) =>
      F.bind(b.cancellableGet) { case (b, cancelB) =>
      F.bind(F.set(ref)(F.map(a)(Left(_)))) { _ =>
      F.bind(F.set(ref)(F.map(b)(Right(_)))) { _ =>
      F.bind(F.get(ref)) {
        case Left(a) => F.map(cancelB)(_ => Left(a))
        case Right(b) => F.map(cancelA)(_ => Right(b))
      }}}}}}
    }

    def raceSame(b: Future[F,A])(implicit F: Async[F]): Pull[F,Nothing,RaceResult[A,Future[F,A]]] =
      self.race(b).map {
        case Left(a) => RaceResult(a, b)
        case Right(a) => RaceResult(a, self)
      }
  }

  def race[F[_]:Async,A](es: Vector[Future[F,A]])
    : Pull[F,Nothing,Focus[A,Future[F,A]]]
    = Future.indexedRace(es) map { case (a, i) => Focus(a, i, es) }

  case class Focus[A,B](get: A, index: Int, v: Vector[B]) {
    def set(b: B): Vector[B] = v.patch(index, List(b), 1)
    def delete: Vector[B] = v.patch(index, List(), 1)
  }

  case class RaceResult[+A,+B](winner: A, loser: B)

  object Future {

    def pure[F[_],A](a: A)(implicit F: Async[F]): Future[F,A] = new Future[F,A] {
      def get = F.pure(a)
      def cancellableGet = F.pure((get, F.pure(())))
    }

    private[fs2] def traverse[F[_],A,B](v: Vector[A])(f: A => F[B])(implicit F: Monad[F])
      : F[Vector[B]]
      = v.reverse.foldLeft(F.pure(Vector.empty[B]))((tl,hd) => F.bind(f(hd)) { b => F.map(tl)(b +: _) })

    private[fs2] def indexedRace[F[_],A](es: Vector[Future[F,A]])(implicit F: Async[F])
      : Pull[F,Nothing,(A,Int)]
      = Pull.eval[F, (A,Int)] {
          F.bind(F.ref[(A,Int)]) { ref =>
            val cancels: F[Vector[(F[Unit],Int)]] = traverse(es zip (0 until es.size)) { case (a,i) =>
              F.bind(a.cancellableGet) { case (a, cancelA) =>
              F.map(F.set(ref)(F.map(a)((_,i))))(_ => (cancelA,i)) }
            }
          F.bind(cancels) { cancels =>
          F.bind(F.get(ref)) { case (a,i) =>
            F.map(traverse(cancels.collect { case (a,j) if j != i => a })(identity))(_ => (a,i))
          }
      }}}
  }
}
