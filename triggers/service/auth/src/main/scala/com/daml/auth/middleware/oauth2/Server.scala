// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.auth.middleware.oauth2

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{HttpCookie, HttpCookiePair}
import akka.http.scaladsl.server.{Directive1, Route}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import com.daml.ledger.api.refinements.ApiTypes.ApplicationId
import com.daml.auth.oauth2.api.{JsonProtocol => OAuthJsonProtocol, Response => OAuthResponse}
import com.typesafe.scalalogging.StrictLogging

import java.util.UUID
import com.daml.auth.middleware.api.{Request, RequestStore, Response}
import com.daml.jwt.{JwtDecoder, JwtVerifierBase}
import com.daml.jwt.domain.Jwt
import com.daml.ledger.api.auth.{AuthServiceJWTCodec, CustomDamlJWTPayload, StandardJWTPayload}
import com.daml.auth.middleware.api.Tagged.{AccessToken, RefreshToken}
import com.daml.ports.{Port, PortFiles}
import scalaz.{-\/, \/-}
import spray.json._

import scala.collection.compat._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

// This is an implementation of the trigger service auth middleware
// for OAuth2 as specified in `/triggers/service/authentication.md`
class Server(config: Config) extends StrictLogging {
  import com.daml.auth.middleware.api.JsonProtocol._
  import com.daml.auth.oauth2.api.JsonProtocol._

  implicit private val unmarshal: Unmarshaller[String, Uri] = Unmarshaller.strict(Uri(_))

  private def toRedirectUri(uri: Uri) =
    config.callbackUri.getOrElse {
      Uri()
        .withScheme(uri.scheme)
        .withAuthority(uri.authority)
        .withPath(Uri.Path./("cb"))
    }

  private val cookieName = "daml-ledger-token"

  private def optionalToken: Directive1[Option[OAuthResponse.Token]] = {
    def f(x: HttpCookiePair) = OAuthResponse.Token.fromCookieValue(x.value)
    optionalCookie(cookieName).map(_.flatMap(f))
  }

  // Check whether the provided token's signature is valid.
  private def tokenIsValid(accessToken: String, verifier: JwtVerifierBase): Boolean = {
    verifier.verify(Jwt(accessToken)).isRight
  }

  // Check whether the provided access token grants at least the requested claims.
  private def tokenProvidesClaims(accessToken: String, claims: Request.Claims): Boolean = {
    for {
      decodedJwt <- JwtDecoder.decode(Jwt(accessToken)).toOption
      tokenPayload <- AuthServiceJWTCodec
        .readFromString(decodedJwt.payload)
        .map {
          case _: StandardJWTPayload =>
            throw new UnsupportedOperationException(
              // TODO (i12388): make auth middlware work with user tokens
              "auth-middleware: user access tokens are not yet supported (https://github.com/digital-asset/daml/issues/12388)."
            )
          case payload: CustomDamlJWTPayload => payload
        }
        .toOption
    } yield {
      (tokenPayload.admin || !claims.admin) &&
      claims.actAs.map(_.toString).toSet.subsetOf(tokenPayload.actAs.toSet) &&
      claims.readAs
        .map(_.toString)
        .toSet
        .subsetOf(tokenPayload.readAs.toSet ++ tokenPayload.actAs.toSet) &&
      ((claims.applicationId, tokenPayload.applicationId) match {
        // No requirement on app id
        case (None, _) => true
        // Token valid for all app ids.
        case (_, None) => true
        case (Some(expectedAppId), Some(actualAppId)) => expectedAppId == ApplicationId(actualAppId)
      })
    }
  }.getOrElse(false)

  private val requestTemplates: RequestTemplates = RequestTemplates(
    config.clientId,
    config.clientSecret,
    config.oauthAuthTemplate,
    config.oauthTokenTemplate,
    config.oauthRefreshTemplate,
  )

  private def onTemplateSuccess(
      request: String,
      tryParams: Try[Map[String, String]],
  ): Directive1[Map[String, String]] =
    tryParams match {
      case Failure(exception) =>
        logger.error(s"Failed to interpret $request request template: ${exception.getMessage}")
        complete(StatusCodes.InternalServerError, s"Failed to construct $request request")
      case Success(params) => provide(params)
    }

  private val auth: Route =
    parameters(Symbol("claims").as[Request.Claims])
      .as[Request.Auth](Request.Auth) { auth =>
        optionalToken {
          case Some(token)
              if tokenIsValid(token.accessToken, config.tokenVerifier) &&
                tokenProvidesClaims(token.accessToken, auth.claims) =>
            complete(
              Response
                .Authorize(
                  accessToken = AccessToken(token.accessToken),
                  refreshToken = RefreshToken.subst(token.refreshToken),
                )
            )
          // TODO[AH] Include a `WWW-Authenticate` header.
          case _ => complete(StatusCodes.Unauthorized)
        }
      }

  private val requests: RequestStore[UUID, Option[Uri]] =
    new RequestStore(config.maxLoginRequests, config.loginTimeout)

  private val login: Route =
    parameters(
      Symbol("redirect_uri").as[Uri] ?,
      Symbol("claims").as[Request.Claims],
      Symbol("state") ?,
    )
      .as[Request.Login](Request.Login) { login =>
        extractRequest { request =>
          val requestId = UUID.randomUUID
          val stored = requests.put(
            requestId,
            login.redirectUri.map { redirectUri =>
              var query = redirectUri.query().to(Seq)
              login.state.foreach(x => query ++= Seq("state" -> x))
              redirectUri.withQuery(Uri.Query(query: _*))
            },
          )
          if (stored) {
            onTemplateSuccess(
              "authorization",
              requestTemplates.createAuthRequest(
                login.claims,
                requestId,
                toRedirectUri(request.uri),
              ),
            ) { params =>
              val query = Uri.Query(params)
              val uri = config.oauthAuth.withQuery(query)
              redirect(uri, StatusCodes.Found)
            }
          } else {
            complete(StatusCodes.ServiceUnavailable)
          }
        }
      }

  private val loginCallback: Route = {
    extractActorSystem { implicit sys =>
      extractExecutionContext { implicit ec =>
        def popRequest(optState: Option[String]): Directive1[Option[Uri]] = {
          val redirectUri = for {
            state <- optState
            requestId <- Try(UUID.fromString(state)).toOption
            redirectUri <- requests.pop(requestId)
          } yield redirectUri
          redirectUri match {
            case Some(redirectUri) => provide(redirectUri)
            case None => complete(StatusCodes.NotFound)
          }
        }

        concat(
          parameters(Symbol("code"), Symbol("state") ?)
            .as[OAuthResponse.Authorize](OAuthResponse.Authorize) { authorize =>
              popRequest(authorize.state) { redirectUri =>
                extractRequest { request =>
                  onTemplateSuccess(
                    "token",
                    requestTemplates.createTokenRequest(authorize.code, toRedirectUri(request.uri)),
                  ) { params =>
                    val entity = FormData(params).toEntity
                    val req = HttpRequest(
                      uri = config.oauthToken,
                      entity = entity,
                      method = HttpMethods.POST,
                    )
                    val tokenRequest =
                      for {
                        resp <- Http().singleRequest(req)
                        tokenResp <-
                          if (resp.status != StatusCodes.OK) {
                            Unmarshal(resp).to[String].flatMap { msg =>
                              Future.failed(
                                new RuntimeException(
                                  s"Failed to retrieve token at ${req.uri} (${resp.status}): $msg"
                                )
                              )
                            }
                          } else {
                            Unmarshal(resp).to[OAuthResponse.Token]
                          }
                      } yield tokenResp
                    onSuccess(tokenRequest) { token =>
                      setCookie(
                        HttpCookie(
                          name = cookieName,
                          value = token.toCookieValue,
                          path = Some("/"),
                          maxAge = token.expiresIn.map(_.toLong),
                          secure = config.cookieSecure,
                          httpOnly = true,
                        )
                      ) {
                        redirectUri match {
                          case Some(uri) =>
                            redirect(uri, StatusCodes.Found)
                          case None =>
                            complete(StatusCodes.OK)
                        }
                      }
                    }
                  }
                }
              }
            },
          parameters(
            Symbol("error"),
            Symbol("error_description") ?,
            Symbol("error_uri").as[Uri] ?,
            Symbol("state") ?,
          )
            .as[OAuthResponse.Error](OAuthResponse.Error) { error =>
              popRequest(error.state) {
                case Some(redirectUri) =>
                  val uri = redirectUri.withQuery {
                    var params = redirectUri.query().to(Seq)
                    params ++= Seq("error" -> error.error)
                    error.errorDescription.foreach(x => params ++= Seq("error_description" -> x))
                    Uri.Query(params: _*)
                  }
                  redirect(uri, StatusCodes.Found)
                case None =>
                  import OAuthJsonProtocol.errorRespFormat
                  complete(StatusCodes.Forbidden, error)
              }
            },
        )
      }
    }
  }

  private val refresh: Route = {
    extractActorSystem { implicit sys =>
      extractExecutionContext { implicit ec =>
        entity(as[Request.Refresh]) { refresh =>
          onTemplateSuccess(
            "refresh",
            requestTemplates.createRefreshRequest(refresh.refreshToken),
          ) { params =>
            val entity = FormData(params).toEntity
            val req =
              HttpRequest(uri = config.oauthToken, entity = entity, method = HttpMethods.POST)
            val tokenRequest = Http().singleRequest(req)
            onSuccess(tokenRequest) { resp =>
              resp.status match {
                // Return access and refresh token on success.
                case StatusCodes.OK =>
                  val authResponse = Unmarshal(resp).to[OAuthResponse.Token].map { token =>
                    Response.Authorize(
                      accessToken = AccessToken(token.accessToken),
                      refreshToken = RefreshToken.subst(token.refreshToken),
                    )
                  }
                  complete(authResponse)
                // Forward client errors.
                case status: StatusCodes.ClientError =>
                  complete(HttpResponse.apply(status = status, entity = resp.entity))
                // Fail on unexpected responses.
                case _ =>
                  onSuccess(Unmarshal(resp).to[String]) { msg =>
                    failWith(
                      new RuntimeException(
                        s"Failed to retrieve refresh token (${resp.status}): $msg"
                      )
                    )
                  }
              }
            }
          }
        }
      }
    }
  }

  def route: Route = concat(
    path("auth") {
      get {
        auth
      }
    },
    path("login") {
      get {
        login
      }
    },
    path("cb") {
      get {
        loginCallback
      }
    },
    path("refresh") {
      post {
        refresh
      }
    },
    path("livez") {
      complete(StatusCodes.OK, JsObject("status" -> JsString("pass")))
    },
  )
}

object Server extends StrictLogging {
  def start(config: Config)(implicit sys: ActorSystem): Future[ServerBinding] = {
    implicit val ec: ExecutionContext = sys.getDispatcher
    for {
      binding <- Http().newServerAt(config.address, config.port).bind(new Server(config).route)
      _ <- config.portFile match {
        case Some(portFile) =>
          PortFiles.write(portFile, Port(binding.localAddress.getPort)) match {
            case -\/(err) =>
              Future.failed(new RuntimeException(s"Failed to create port file: ${err.toString}"))
            case \/-(()) => Future.successful(())
          }
        case None => Future.successful(())
      }
    } yield binding
  }

  def stop(f: Future[ServerBinding])(implicit ec: ExecutionContext): Future[Done] =
    f.flatMap(_.unbind())
}
