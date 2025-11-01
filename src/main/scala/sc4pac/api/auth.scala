package io.github.memo33
package sc4pac
package api

import zio.{ZIO, Task, UIO, IO}
import zio.Ref
import zio.http.*
import zio.Config.Secret
import upickle.default as UP
// import upickle.default.{ReadWriter, readwriter, stringKeyRW}

enum AuthScope {
  case open
  case read
  case write
}
object AuthScope {
  val all = Set(open, read, write)
  def parse(text: String): Option[Set[AuthScope]] = {
    val scopes = text.split(" ").filter(_.nonEmpty).map(s => util.Try(AuthScope.valueOf(s)))
    Option.when(scopes.forall(_.isSuccess))(scopes.map(_.get).toSet)
  }
}

case class ClientCredentials(client_id: String, client_secret: Secret) derives UP.ReadWriter
object ClientCredentials {
  given clientSecretRw: UP.ReadWriter[Secret] =
    UP.readwriter[String].bimap[Secret](_.stringValue, Secret(_))
}

case class UserInfo(profilesDir: ProfilesDir)
object UserInfo {
  val toProfile = zio.ZLayer.fromFunction((userInfo: UserInfo) => userInfo.profilesDir)
}

final case class Token(accessToken: Secret, csrf: Option[Secret])

trait TokenService {
  def issueAccessToken(scopes: Set[AuthScope], credentials: Credentials, withCsrf: Boolean): IO[ErrStr, Token]
  def verifyAccessToken(token: Token, scope: AuthScope): IO[ErrStr, UserInfo]
}
object TokenService {
  def live(profilesDir: ProfilesDir, credentials: Credentials): zio.ZLayer[Any, Nothing, TokenService] = zio.ZLayer.fromZIO {
    for {
      initialCredentials <- Ref.make(Set(credentials))
      tokenStore <- Ref.make(Map.empty[Token, Set[AuthScope]])
    } yield InmemoryTokenService(initialCredentials, tokenStore, profilesDir)
  }

  def generateSecureToken(short: Boolean = false): UIO[Secret] =
    ZIO.succeed {
      val random = new java.security.SecureRandom()
      val bytes  = new Array[Byte](if (short) 16 else 32)
      random.nextBytes(bytes)
      Secret(java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes))
    }
}
class InmemoryTokenService(private[sc4pac] val initialCredentials: Ref[Set[Credentials]], private[sc4pac] val tokenStore: Ref[Map[Token, Set[AuthScope]]], profilesDir: ProfilesDir) extends TokenService {

  def issueAccessToken(scopes: Set[AuthScope], credentials: Credentials, withCsrf: Boolean): IO[ErrStr, Token] =
    for {
      _ <- initialCredentials.modifySome(default = false) { case known if known.contains(credentials) => (true, known.excl(credentials)) }  // one-time use credentials
            .filterOrFail(_ == true)("Invalid or expired client credentials.")
      csrf <- ZIO.when(withCsrf)(TokenService.generateSecureToken())
      token <- TokenService.generateSecureToken().map(Token(_, csrf = csrf))
      _ <- tokenStore.update { tokens => tokens + (token -> scopes) }
    } yield token

  def verifyAccessToken(token: Token, scope: AuthScope): IO[ErrStr, UserInfo] =
    for {
      _  <- tokenStore.get.map(_.get(token))
              .someOrFail("Invalid or expired token.")
              .filterOrFail(_.contains(scope))(s"Token lacks permission for scope $scope.")
    } yield UserInfo(profilesDir)
}

// https://www.rfc-editor.org/rfc/rfc6749#section-5.2
enum TokenEndpointError {
  case invalid_request
  case invalid_client
  case invalid_grant
  case unauthorized_client  // TODO currently not used
  case unsupported_grant_type
  case invalid_scope  // TODO currently not used
}

trait AuthMiddleware { self: Api =>

  val realm = "User Profile"

  // For websockets, we allow passing the token in the query instead of an
  // Authorization header, for compatibility with web browser Javascript, see https://github.com/whatwg/websockets/issues/16#issuecomment-332065542
  def tokenAuth(scope: AuthScope, allowFromQuery: Boolean = false): HandlerAspect[TokenService, UserInfo] =
    HandlerAspect.interceptIncomingHandler {
      handler { (req: Request) =>
        val tokenOpt =
          req.header(Header.Authorization) match {
            case Some(Header.Authorization.Bearer(token)) => Some(token)
            case _ if allowFromQuery => req.url.queryParams.getAll("token").headOption.map(Secret(_))
            case _ => None
          }
        val cookieOpt = req.cookie(Constants.accessTokenCookieName).map(cookie => Secret(cookie.content))
        (tokenOpt match {
          case Some(token) =>
            ZIO.fromOption(cookieOpt)
              .flatMap(cookie => ZIO.serviceWithZIO[TokenService](_.verifyAccessToken(Token(accessToken = cookie, csrf = Some(token)), scope)))
              .orElse(ZIO.serviceWithZIO[TokenService](_.verifyAccessToken(Token(accessToken = token, csrf = None), scope)))
              .map(userInfo => (req, userInfo))
              .mapError(errMsg => ErrorMessage.UnauthorizedRequest("Authentication failed", errMsg))
          case None =>
            ZIO.fail(ErrorMessage.UnauthorizedRequest(
              "missing Authorization header",
              "Add Authorization header with Bearer access token" + (if (allowFromQuery) " or add 'token' query parameter" else ""),
            ))
        }).mapError { (e: ErrorMessage) =>
          jsonResponse(e)
            .addHeaders(Headers(Header.WWWAuthenticate.Bearer(realm, scope = Some(scope.toString))))
            .status(Status.Unauthorized)
        }
      }
    }

  def authEndpoints: Routes[TokenService, Nothing] = Routes(

    Method.POST / "token" -> handler { (req: Request) =>
      req.header(Header.Authorization) match {
        case Some(Header.Authorization.Basic(clientId, clientSecret)) =>
          val task: ZIO[TokenService, TokenEndpointError, Response] =
            val credentials = Credentials(uname = clientId, upassword = clientSecret)
            import scala.jdk.DurationConverters.ScalaDurationOps
            for {
              form <- req.body.asURLEncodedForm.orElseFail(TokenEndpointError.invalid_request)
              asCookie <- ZIO.succeed(form.get("grant_type"))
                .someOrFail(TokenEndpointError.invalid_request)
                .flatMap(_.asText.orElseFail(TokenEndpointError.unsupported_grant_type))
                .filterOrFail(s => s == "client_credentials" || s == "client_credentials_cookie_sc4pac")(TokenEndpointError.unsupported_grant_type)
                .map(_ == "client_credentials_cookie_sc4pac")
              // TODO parse scopes from form? (return invalid_scope)
              scopes = AuthScope.all  // client credentials have permission for all scopes
              token <- ZIO.serviceWithZIO[TokenService](_.issueAccessToken(scopes, credentials, withCsrf = asCookie)
                .orElseFail(TokenEndpointError.invalid_grant))
              resp =
                if (!asCookie)
                  jsonResponse(ujson.Obj(  // see https://www.rfc-editor.org/rfc/rfc6749#section-5.1
                    "access_token" -> token.accessToken.stringValue,
                    "token_type" -> "Bearer",  // case-insensitive
                    "expires_in" -> Constants.cookieExpirationTime.toSeconds,
                    "scope" -> scopes.mkString(" ")
                  )).addHeaders(Headers(Header.CacheControl.NoStore, Header.Pragma.NoCache))
                else
                  jsonResponse(ujson.Obj(
                    "csrf_token" -> token.csrf.get.stringValue,
                    "token_type" -> "Bearer",  // case-insensitive
                    "expires_in" -> Constants.cookieExpirationTime.toSeconds,
                    "scope" -> scopes.mkString(" ")
                  )).addHeaders(Headers(
                    Header.CacheControl.NoStore,
                    Header.Pragma.NoCache,
                    Header.SetCookie(Cookie.Response(
                      name = Constants.accessTokenCookieName,  // see https://datatracker.ietf.org/doc/html/draft-ietf-oauth-browser-based-apps#section-6.1.3.2
                      content = token.accessToken.stringValue,
                      path = Some(Path("/")),
                      isSecure = true,
                      isHttpOnly = true,
                      maxAge = Some(Constants.cookieExpirationTime.toJava),
                      sameSite = Some(Cookie.SameSite.Strict),
                    )),
                  ))
            } yield resp
          task.catchAll(err => ZIO.succeed(  // see https://www.rfc-editor.org/rfc/rfc6749#section-5.2
            jsonResponse(ujson.Obj(
              "error" -> err.toString,
              "error_uri" -> "https://www.rfc-editor.org/rfc/rfc6749#section-5.2",
            )).addHeaders(Headers(Header.CacheControl.NoStore, Header.Pragma.NoCache))
              .status(Status.BadRequest)
          ))
        case _ => ZIO.succeed(
          jsonResponse(ujson.Obj(
            "error" -> TokenEndpointError.invalid_client.toString,
            "error_uri" -> "https://www.rfc-editor.org/rfc/rfc6749#section-5.2",
          )).addHeaders(Headers(
            Header.CacheControl.NoStore,
            Header.Pragma.NoCache,
            Header.WWWAuthenticate.Basic(realm = Some(realm)),  // TODO charset is incorrectly encoded by zio-http: https://github.com/zio/zio-http/blob/edba470b9eecbd7307f69a5c17940a37751b7a8f/zio-http/shared/src/main/scala/zio/http/Header.scala#L4687
          )).status(Status.Unauthorized)
        )
      }
    },

  )

}
