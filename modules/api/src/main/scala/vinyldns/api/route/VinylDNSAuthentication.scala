/*
 * Copyright 2018 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vinyldns.api.route

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server.AuthenticationFailedRejection.Cause
import akka.http.scaladsl.server.{AuthenticationFailedRejection, RequestContext}
import vinyldns.api.VinylDNSConfig
import vinyldns.api.domain.auth.{
  AuthPrincipal,
  AuthPrincipalProvider,
  MembershipAuthPrincipalProvider
}
import vinyldns.core.crypto.{Crypto, CryptoAlgebra}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.matching.Regex

sealed abstract class VinylDNSAuthenticationError(msg: String) extends Throwable(msg)
final case class AuthMissing(msg: String) extends VinylDNSAuthenticationError(msg)
final case class AuthRejected(reason: String) extends VinylDNSAuthenticationError(reason)

trait VinylDNSAuthentication extends Monitored {
  val authenticator: Aws4Authenticator
  val authPrincipalProvider: AuthPrincipalProvider

  /**
    * Gets the auth header from the request.  If the auth header is not found then the
    * AuthMissing is thrown, which yields a CredentialsMissing
    *
    * @return A Future containing the value of the auth header
    */
  def getAuthHeader(ctx: RequestContext): Future[String] =
    ctx.request.headers
      .find { header =>
        header.name.compareToIgnoreCase("Authorization") == 0
      }
      .map(header => Future.successful(header.value))
      .getOrElse(Future.failed(AuthMissing("Authorization header not found")))

  /**
    * Parses the auth header into an Aws Regex.Match.  If the auth header cannot be parsed, an
    * AuthRejected is thrown which will result in a CredentialsRejected
    *
    * @return A Future containing a Regex.Match on the auth header
    */
  def parseAuthHeader(header: String): Future[Regex.Match] =
    Aws4Authenticator
      .parseAuthHeader(header)
      .map(Future.successful)
      .getOrElse(Future.failed(AuthRejected("Authorization header could not be parsed")))

  /**
    * Gets the access key from the request.  Normalizes the exceptions coming out of the authenticator
    *
    * @return A Future with the access key in the Authorization Header
    */
  def getAccessKey(header: String): Future[String] =
    Future.fromTry {
      Try(authenticator.extractAccessKey(header))
        .recover {
          case mt: MissingAuthenticationTokenException =>
            throw AuthMissing(mt.msg)
          case e: Throwable =>
            throw AuthRejected(e.getMessage)
        }
    }

  /**
    * Validates the signature on the request
    *
    * @return Successful future if ok; Failure with an AuthRejected otherwise
    */
  def validateRequestSignature(
      req: HttpRequest,
      secretKey: String,
      authHeaderRegex: Regex.Match,
      content: String): Future[Unit] =
    authHeaderRegex match {
      case auth if authenticator.authenticateReq(req, auth.subgroups, secretKey, content) =>
        Future.successful(())
      case _ =>
        Future.failed(AuthRejected(s"Request signature could not be validated"))
    }

  /**
    * Authenticates the request:
    * - gets the Authorization Http Header from the request
    * - parse the Http Header into a RegEx
    * - extracts the access key from the Authorization Http Header
    * - looks up the account based on the access key
    * - validates the signature of the request
    * - looks up the authorized accounts for the signed in user
    * - builds the auth principal
    *
    * If any validations fail that we expect, will yield a Failure with an AuthMissing or AuthRejected; otherwise
    * unanticipated exceptions will simply bubble out and result as 500s or 503s
    *
    * @param ctx The Http Request Context
    * @return A Future containing the AuthPrincipal for the request.
    */
  def authenticate(ctx: RequestContext, content: String)(
      implicit executionContext: ExecutionContext): Future[AuthPrincipal] =
    for {
      authHeader <- getAuthHeader(ctx)
      regexMatch <- parseAuthHeader(authHeader)
      accessKey <- getAccessKey(authHeader)
      authPrincipal <- getAuthPrincipal(accessKey)
      _ <- validateRequestSignature(
        ctx.request,
        decryptSecret(authPrincipal.secretKey),
        regexMatch,
        content)
    } yield authPrincipal

  def decryptSecret(
      str: String,
      encryptionEnabled: Boolean = VinylDNSConfig.encryptUserSecrets,
      crypto: CryptoAlgebra = Crypto.instance): String =
    if (encryptionEnabled) crypto.decrypt(str) else str

  def getAuthPrincipal(accessKey: String)(
      implicit executionContext: ExecutionContext): Future[AuthPrincipal] =
    authPrincipalProvider.getAuthPrincipal(accessKey).map {
      _.getOrElse(throw AuthRejected(s"Account with accessKey $accessKey specified was not found"))
    }
}

class VinylDNSAuthenticator(
    val authenticator: Aws4Authenticator,
    val authPrincipalProvider: AuthPrincipalProvider)
    extends VinylDNSAuthentication {

  def apply(ctx: RequestContext, content: String)(
      implicit ec: ExecutionContext): Future[Either[Cause, AuthPrincipal]] =
    authenticate(ctx, content)
      .map(authPrincipal => Right(authPrincipal))
      .recover {
        case _: AuthMissing =>
          Left(AuthenticationFailedRejection.CredentialsMissing)
        case _: AuthRejected =>
          Left(AuthenticationFailedRejection.CredentialsRejected)
        case e: Throwable =>
          // throw here as some unexpected exception occurred
          throw e
      }
}

object VinylDNSAuthenticator {
  lazy val aws4Authenticator = new Aws4Authenticator
  lazy val authPrincipalProvider = MembershipAuthPrincipalProvider()
  lazy val authenticator = new VinylDNSAuthenticator(aws4Authenticator, authPrincipalProvider)

  def apply(ctx: RequestContext, content: String)(
      implicit ec: ExecutionContext): Future[Either[Cause, AuthPrincipal]] =
    authenticator.apply(ctx, content)
}
