package com.dummyc0m.pylon.panel

import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.eventbus.Message
import io.vertx.core.http.HttpHeaders
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.mongo.MongoAuth
import io.vertx.ext.mongo.MongoClient
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.*
import io.vertx.ext.web.handler.sockjs.BridgeOptions
import io.vertx.ext.web.handler.sockjs.PermittedOptions
import io.vertx.ext.web.handler.sockjs.SockJSHandler
import io.vertx.ext.web.sstore.LocalSessionStore
import io.vertx.kotlin.lang.json.get

/**
 * Created by Dummy on 8/3/16.
 */
class PylonPanelWeb(val args: Array<String>) {
    fun launch() {
        var mongo: MongoClient
        Vertx.clusteredVertx(VertxOptions()) {vertxResult ->
            if (vertxResult.succeeded()) {
                with(vertxResult.result()) {
                    val config = Vertx.currentContext().config()
                    val uri = config.getString("mongo_uri", "mongodb://localhost:27017")
                    //mongodb://user1:pwd1@host1/?authSource=db1
                    val db = config.getString("mongo_db", "test")
                    val mongoConfig = JsonObject()
                            .put("connection_string", uri)
                            .put("db_name", db)

                    mongo = MongoClient.createShared(this, mongoConfig)

                    val router = Router.router(this)

                    // We need cookies and sessions
                    router.route().handler(CookieHandler.create())
                    router.route().handler(BodyHandler.create())
                    router.route().handler(SessionHandler.create(LocalSessionStore.create(this)))

                    // Simple auth service which uses a properties file for user/role info
//                    val jwt = JWTAuth.create(this, JsonObject()
//                    .put("keyStore", JsonObject()
//                            .put("type", "jceks")
//                            .put("path", "keystore.jceks")
//                            .put("password", "secret")))//ShiroAuth.create(vertx, ShiroAuthRealmType.PROPERTIES, JsonObject())
                    val mongoAuth = MongoAuth.create(mongo, JsonObject())

                    // We need a user session handler too to make sure the user is stored in the session between requests
                    router.route().handler(UserSessionHandler.create(mongoAuth))

                    router.post("/login").handler { ctx ->
                        val credentials = ctx.bodyAsJson
                        if (credentials == null) {
                            // bad request
                            ctx.fail(400)
                            return@handler
                        }

                        mongoAuth.authenticate(credentials, { login ->
                            if (login.failed()) {
                                ctx.fail(403)
                                return@authenticate
                            }
//                    jwt.generateToken(JsonObject(), JWTOptions()
//                            .setExpiresInSeconds(config.getLong("expiration", 60L))
//                            .setPermissions())

                            ctx.setUser(login.result())
                            ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json").end("{}")
                        })
                    }

                    router.route("/logout").handler { context ->
                        context.clearUser()
                        //context.response().putHeader("location", "/").setStatusCode(302).end()
                    }

                    router.route("/eventbus/*").handler { ctx ->
                        if (ctx.user() == null) {
                            ctx.fail(401)
                        } else {
                            ctx.next()
                        }
//                ctx.user().isAuthorised("commit_code", { res ->
//                    if (res.succeeded()) {
//                        val hasPermission = res.result()
//                    } else {
//                        // Failed to
//                        ctx.fail(401)
//                    }
//                })
                    }

                    router.route("/eventbus/*").handler(BasicAuthHandler.create(mongoAuth))

                    // Allow all outbound traffic
                    val options = BridgeOptions().addInboundPermitted(PermittedOptions().setAddress("vtoons.listAlbums"))
                            .addInboundPermitted(PermittedOptions()
                                    .setAddressRegex("^node[.][a-zA-Z0-9_-]*[.]queryServer$")
                                    .setRequiredAuthority("server.query"))
                            .addInboundPermitted(PermittedOptions()
                                    .setAddressRegex("^node[.][a-zA-Z0-9_-]*[.]upsertServer$")
                                    .setRequiredAuthority("server.upsert"))
                            .addInboundPermitted(PermittedOptions()
                                    .setAddressRegex("^node[.][a-zA-Z0-9_-]*[.]destroyServer$")
                                    .setRequiredAuthority("server.destroy"))
                            .addInboundPermitted(PermittedOptions()
                                    .setAddress("web.upsertNode")
                                    .setRequiredAuthority("web.upsertNode"))
                            .addInboundPermitted(PermittedOptions()
                                    .setAddress("web.destroyNode")
                                    .setRequiredAuthority("web.destroyNode"))
                            .addInboundPermitted(PermittedOptions()
                                    .setAddress("web.listServers")
                                    .setRequiredAuthority("web.listServers"))
                            .addInboundPermitted(PermittedOptions()
                                    .setAddress("web.listNodes")
                                    .setRequiredAuthority("web.listNodes"))
                            .addInboundPermitted(PermittedOptions()
                                    .setAddress("web.listUsers")
                                    .setRequiredAuthority("web.listUsers"))
                            .addInboundPermitted(PermittedOptions()
                                    .setAddress("web.upsertUser")
                                    .setRequiredAuthority("web.upsertUser"))
                            .addInboundPermitted(PermittedOptions()
                                    .setAddress("web.destroyUser")
                                    .setRequiredAuthority("web.destroyUser"))
                            .addOutboundPermitted(PermittedOptions())

                    router.route("/eventbus/*").handler { ctx ->
                        val messageBody: JsonObject? = ctx.bodyAsJson?.getJsonObject("body")
                        if (messageBody !== null) {
                            val serverOperated = messageBody.getString("serverName")
                            val nodeOperated = messageBody.getString("nodeName")

                        }
                    }

                    router.route("/eventbus/*").handler(SockJSHandler.create(this).bridge(options))
                    //don't forget static pages via nginx

                    // the app works 100% realtime
                    eventBus().consumer<JsonObject>("web.upsertNode") { obj ->
                        val req = obj.body()
                        val nodeName = req.getString("nodeName")
                        mongo.save("nodes", JsonObject().put("nodeName", nodeName)) { res ->
                            if (res.succeeded()) {
                                obj.reply(JsonObject().put("result", "success"))
                            } else {
                                obj.reply(JsonObject().put("result", "failure"))
                                res.cause().printStackTrace()
                            }
                        }

                    }

                    eventBus().consumer<JsonObject>("web.destroyNode") { obj ->
                        val req = obj.body()
                        val nodeName = req.getString("nodeName")
                        if (nodeName === null || nodeName.isBlank()) {
                            mongo.removeDocument("nodes", JsonObject().put("nodeName", nodeName)) { res ->
                                if (res.succeeded()) {
                                    obj.reply(JsonObject().put("result", "success"))
                                } else {
                                    obj.reply(JsonObject().put("result", "failure"))
                                    res.cause().printStackTrace()
                                }
                            }
                        }
                    }

                    eventBus().consumer<JsonObject>("web.listServers") { obj ->
                        obj.headers()
                    }

                    createHttpServer().requestHandler { req ->
                        router.accept(req)
                    }.listen(8080)
                }
            }
        }
    }
}