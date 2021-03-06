package app.infrastructure.repository

import cats.data.EitherT
import cats.implicits._
import app.domain.cash.NonNegativeAmount
import app.domain.error.DomainError
import app.domain.user.error.UserNotFoundError
import app.domain.user.{UserBalance, UserBalanceRepository, UserId}

import scala.collection.concurrent.{Map, TrieMap}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class UserInMemoryBalanceRepository extends UserBalanceRepository {
  val map: Map[UserId, UserBalance] = TrieMap()

  override def findById(userId: UserId): EitherT[Future, DomainError, UserBalance] =
    map.get(userId).toRight[DomainError](UserNotFoundError(userId)).toEitherT

  override def save(user: UserBalance): EitherT[Future, DomainError, UserId] = {
    map.put(user.id, user)
    Right(user.id).toEitherT
  }
}
