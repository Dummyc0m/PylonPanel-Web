package com.dummyc0m.pylon.panel

import io.vertx.ext.auth.AuthProvider
import io.vertx.ext.auth.mongo.MongoAuth
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.AuthHandler
import io.vertx.ext.web.handler.impl.AuthHandlerImpl

/**
 * Created by Dummy on 8/4/16.
 */
interface MongoAuthHandler: AuthHandler {
    companion object {
        fun create(authProvider: MongoAuth): MongoAuthHandler {
            return MongoAuthHandlerImpl(authProvider)
        }
    }
}

class MongoAuthHandlerImpl(authProvider: AuthProvider?) : MongoAuthHandler, AuthHandlerImpl(authProvider) {
    override fun handle(context: RoutingContext) {
        val user = context.user()
        if (user != null) {
            this.authorise(user, context)
        } else {
            context.fail(401)
        }
    }
}