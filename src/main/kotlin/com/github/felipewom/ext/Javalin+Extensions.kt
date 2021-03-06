package com.github.felipewom.ext

import com.github.felipewom.commons.*
import com.github.felipewom.i18n.I18nKeys
import com.github.felipewom.i18n.I18nProvider
import com.github.felipewom.i18n.I18nPtBRDefault
import com.github.felipewom.security.Roles
import com.github.felipewom.springboot.HealthHandler
import com.github.felipewom.utils.gson.GsonUtils
import com.github.felipewom.utils.gson.ListOfJson
import com.google.gson.Gson
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder
import io.javalin.core.JavalinConfig
import io.javalin.core.security.Role
import io.javalin.core.security.SecurityUtil
import io.javalin.core.util.RouteOverviewPlugin
import io.javalin.core.validation.JavalinValidation
import io.javalin.core.validation.Validator
import io.javalin.http.*
import io.javalin.plugin.json.FromJsonMapper
import io.javalin.plugin.json.JavalinJson
import io.javalin.plugin.json.ToJsonMapper
import io.javalin.plugin.openapi.OpenApiOptions
import io.javalin.plugin.openapi.OpenApiPlugin
import io.javalin.plugin.openapi.dsl.OpenApiDocumentation
import io.javalin.plugin.openapi.jackson.JacksonModelConverterFactory
import io.javalin.plugin.openapi.jackson.JacksonToJsonMapper
import io.javalin.plugin.openapi.ui.SwaggerOptions
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import org.eclipse.jetty.http.HttpStatus
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.koin.core.context.stopKoin
import java.rmi.activation.UnknownObjectException


@Throws(BadRequestResponse::class)
inline fun <reified T : Any?> Context.getParamIdValidator(): Validator<T> =
    this.pathParam(ApiConstants.ID, T::class.java)

inline fun <reified DTO : Any> Context.bodyAsList(): List<DTO>? {
    return try {
        val deserialized: List<DTO> = Gson().fromJson(this.body(), ListOfJson<DTO>(DTO::class.java))
        return deserialized
    } catch (e: Exception) {
        logger.error("[ERROR]GsonUtils::deserialize=>${e.message}")
        null
    }
}

inline fun <reified DTO : Any> Context.bodyAsListValidator() = try {
    Validator(this.bodyAsList<DTO>(), "Request body as ${DTO::class.simpleName}")
} catch (e: Exception) {
    throw BadRequestResponse("Couldn't deserialize body to ${DTO::class.simpleName}")
}

fun Context.getPageable(): Pageable = use(Pageable::class.java)

fun Context.getPageableExposed(): PageableExposed = use(PageableExposed()::class.java)

fun Context.getPageableValidator(): Validator<Pageable> {
    val pageable = this.use(Pageable::class.java)
    return Validator(pageable)
}

fun Context.getPageablePageableExposedValidator(): Validator<PageableExposed> {
    val pageable = this.use(PageableExposed::class.java)
    return Validator(pageable)
}

fun Context.deserializePageable(): Pageable {
    return Pageable(this)
}

fun Context.deserializePageableExposed(): PageableExposed {
    return PageableExposed(this)
}

fun Context.jsonOrNull(obj: Any?) = when {
    obj != null -> contentType(ApiConstants.JSON_MIME).result(JavalinJson.toJson(obj))
    else -> contentType(ApiConstants.JSON_MIME).result("")
}

fun Context.ok(body: Any? = null) = this.status(HttpStatus.OK_200).jsonOrNull(body)

fun Context.noContent() = this.status(HttpStatus.NO_CONTENT_204).result("")

fun Context.created(value: Any? = null) = this.status(HttpStatus.CREATED_201).jsonOrNull(value)

fun Context.badRequest(message: String? = I18nKeys.error_bad_request) {
    val responseMessage = this.getI18n(message ?: I18nKeys.error_bad_request)
    this.json(
        ResponseError(
            errors = mapOf(ApiConstants.BAD_REQUEST_400 to listOf(responseMessage))
        )
    ).status(HttpStatus.BAD_REQUEST_400)
}

fun Context.badCredentials(message: String? = I18nKeys.error_bad_credentials) {
    val responseMessage = this.getI18n(message ?: I18nKeys.error_bad_credentials)
    this.json(
        ResponseError(
            errors = mapOf(ApiConstants.UNAUTHORIZED_401 to listOf(responseMessage))
        )
    ).status(HttpStatus.UNAUTHORIZED_401)
}

fun Context.failureWith(error: ResultHandler.Failure?) {
    if (error == null) {
        return this.badRequest()
    }
    return when {
        error.throwable is UnauthorizedResponse -> this.badCredentials(error.message)
        error.message.isNotNullOrBlank() -> this.badRequest(error.message)
        else -> this.badRequest()
    }
}

fun Context.getCookie(): String {
    val cookieMap = this.cookieMap()
    if (cookieMap.isNullOrEmpty()) {
        return ""
    }
    return cookieMap.asCookieString()
}

fun Context.getTenantId(): String? = this.header(ApiConstants.TENANT_KEY_HEADER)

fun Context.getCookieFromJwt(): Map<String, String> {
    val token = this.getJWTPrincipal()
    val cookieToken = "${ApiConstants.SESSION_COOKIE}=$token;"
    return mapOf(ApiConstants.COOKIE to cookieToken)
}


fun Context.getPrincipal(): String = this.getJWTPrincipal()

fun Context.getJWTPrincipal(): String =
    this.attribute<String>(ApiConstants.JWT_SUBJECT_ATTR) ?: throw UnauthorizedResponse()

fun Context.getJWTId(): String =
    this.attribute<String>(ApiConstants.JWT_CLAIM_ID_ATTR) ?: throw UnauthorizedResponse()

fun Context.getJWTEmail(): String =
    this.attribute<String>(ApiConstants.JWT_CLAIM_EMAIL_ATTR)
        ?: throw UnauthorizedResponse()

fun Context.getJWTPrincipalOrThrow(): String =
    this.attribute<String>(ApiConstants.JWT_CLAIM_EMAIL_ATTR)
        ?: throw UnauthorizedResponse()

fun Context.getJWT(): String {
    return this.header(ApiConstants.JWT_AUTHORIZATION_HEADER)?.let { it.split("${ApiConstants.JWT_BEARER_TOKEN} ")[1] }
        ?: throw UnauthorizedResponse()
}

fun Javalin.configureGsonMapper() {
    JavalinJson.fromJsonMapper = fromJsonMapper
    JavalinJson.toJsonMapper = toJsonMapper
}

val fromJsonMapper = object : FromJsonMapper {
    override fun <T> map(json: String, targetClass: Class<T>): T = GsonUtils.gson.fromJson(json, targetClass)
}
val toJsonMapper = object : ToJsonMapper {
    override fun map(obj: Any): String = GsonUtils.gson.toJson(obj)
}

fun configureJavalinServer(appDeclaration: JavalinConfig.() -> Unit): Javalin {
    val anonymousRoutes: Set<Role> = SecurityUtil.roles(Roles.ANYONE)
    val appProperties: AppProperties by injectDependency()
    val javalin = Javalin.create { config ->
        config.registerPlugin(RouteOverviewPlugin(ApiConstants.OVERVIEW_PATH));
        config.registerPlugin(OpenApiPlugin(getOpenApiOptions(appProperties)));
        config.contextPath = appProperties.env.context
        config.enableCorsForAllOrigins()
        config.autogenerateEtags = true
        config.defaultContentType = ApiConstants.JSON_MIME
        // set debug logging if env variable ENV=development is present
        if (appProperties.env.isDev() || appProperties.env.isTest()) {
            config.enableDevLogging()
        }
        appDeclaration(config)
    }
    // configure json object mapper
    configureJsonMapper()
    javalin.registerExceptionHandlers()
    registerValidations()
    javalin.before { ctx ->
        ctx.register(Pageable::class.java, ctx.deserializePageable())
        ctx.register(PageableExposed::class.java, ctx.deserializePageableExposed())
    }
    javalin.routes {
        anonymousRoutes.also { anonymous ->
            ApiBuilder.get(
                "ping",
                io.javalin.plugin.openapi.dsl.documented(
                    documentation = OpenApiDocumentation()
                        .result<String>(
                            "${HttpStatus.OK_200}",
                            applyUpdates = {
                                val mediaType = MediaType()
                                mediaType.example = "pong!"
                                it.content = Content().addMediaType(ApiConstants.TEXT_PLAIN_MIME, mediaType)
                            }
                        )
                ) { it.result("pong!") },
                anonymous
            )
            ApiBuilder.path("admin") {
                ApiBuilder.get(
                    "info",
                    io.javalin.plugin.openapi.dsl.documented(
                        documentation = OpenApiDocumentation()
                            .result<String>(
                                status = "${HttpStatus.OK_200}",
                                applyUpdates = {
                                    val mediaType = MediaType()
                                    mediaType.example = "{'status': 'up!'}"
                                    it.content = Content().addMediaType(ApiConstants.JSON_MIME, mediaType)
                                }
                            ),
                        handle = { HealthHandler.info(it) }
                    ),
                    anonymous
                )
            }
        }
    }
    javalin.after { ctx ->
        ctx.header("Server", "Powered by ${getProperty("powered_by", "Javalin")}")
        ctx.header(ApiConstants.API_VERSION_HEADER, appProperties.env.projectVersion)
    }
    javalin.events {
        it.serverStarted {
            logger.info("Server is starting in ${appProperties.env.stage.toUpperCase()}")
            logger.info("____________________________________________________")
            appProperties.env.print()
            logger.info("____________________________________________________")
            logger.info("DEFAULT_TIMEZONE:${DEFAULT_TIMEZONE}")
            logger.info("____________________________________________________")
        }
        it.serverStopped {
            stopKoin()
        }
    }
    return javalin
}

fun Context.isPermittedRoute(permittedRoles: Set<Role>): Boolean {
    val appProperties: AppProperties by injectDependency()
    val isSwaggerAvailable = appProperties.env.isDev() && (listOf(
        appProperties.env.context + ApiConstants.OVERVIEW_PATH,
        appProperties.env.swaggerContextPath,
        appProperties.env.swaggerJsonPath
    ).contains(this.path()))
    return isSwaggerAvailable || permittedRoles.contains(Roles.ANYONE)
}

private fun getOpenApiOptions(appProperties: AppProperties): OpenApiOptions {
    val applicationInfo = Info()
        .version(appProperties.env.projectVersion)
        .title(appProperties.env.projectName)
        .description(appProperties.env.projectDescription)
        .contact(Contact().name(appProperties.env.swaggerContactName))
    return OpenApiOptions(applicationInfo)
        .toJsonMapper(JacksonToJsonMapper())
        .modelConverterFactory(JacksonModelConverterFactory())
        .roles(SecurityUtil.roles(Roles.AUTHENTICATED))
        .activateAnnotationScanningFor(ApiConstants.ROOT_PACKAGE)
        .swagger(SwaggerOptions(appProperties.env.swaggerContextPath).title(appProperties.env.projectName))
        .defaultDocumentation { documentation ->
            documentation.json<ResponseError>(I18nPtBRDefault.translate.getValue(I18nKeys.error_unknow_server_error))
        }
        .path(appProperties.env.swaggerJsonPath)
}

private fun registerValidations() {
    JavalinValidation.register(Any::class.java) {
        try {
            GsonUtils.deserialize(it, Any::class.java)
        } catch (e: Exception) {
            it
        }
    }
}

fun configureJsonMapper() {
    JavalinJson.fromJsonMapper = fromJsonMapper
    JavalinJson.toJsonMapper = toJsonMapper
}

fun Javalin.registerExceptionHandlers() {
    this.exception(RuntimeException::class.java) { e, ctx ->
        logger.error("Exception occurred for req -> ${ctx.url()}", e)
        val errorMessage = ctx.getI18n(e.message ?: I18nKeys.error_internal_server_error)
        val errorList = mutableListOf(errorMessage)
        if (e.localizedMessage != errorMessage) {
            errorList.add(e.localizedMessage)
        }
        val error = ResponseError(mapOf("InternalServerError" to errorList))
        ctx.json(error).status(HttpStatus.INTERNAL_SERVER_ERROR_500)
    }
    this.exception(Exception::class.java) { e, ctx ->
        logger.error("Exception occurred for req -> ${ctx.url()}", e)
        val errorMessage = ctx.getI18n(e.message ?: I18nKeys.error_internal_server_error)
        val errorList = mutableListOf(errorMessage)
        if (e.localizedMessage != errorMessage) {
            errorList.add(e.localizedMessage)
        }
        val error = ResponseError(mapOf("InternalServerError" to errorList))
        ctx.json(error).status(HttpStatus.INTERNAL_SERVER_ERROR_500)
    }
    this.exception(ExposedSQLException::class.java) { e, ctx ->
        logger.error("Exception occurred for req -> ${ctx.url()}", e)
        val errorMessage = ctx.getI18n(e.message ?: I18nKeys.error_internal_server_error)
        val errorList = mutableListOf(errorMessage)
        if (e.localizedMessage != errorMessage) {
            errorList.add(e.localizedMessage)
        }
        val error = ResponseError(mapOf("InternalServerError" to errorList))
        ctx.json(error).status(HttpStatus.INTERNAL_SERVER_ERROR_500)
    }
    this.exception(SecurityException::class.java) { e, ctx ->
        logger.info("SecurityException occurred for req -> ${ctx.url()}")
        val errorMessage = ctx.getI18n(e.message ?: I18nKeys.error_user_not_authenticated)
        val errorList = mutableListOf(errorMessage)
        if (e.localizedMessage != errorMessage) {
            errorList.add(e.localizedMessage)
        }
        val error = ResponseError(mapOf("SecurityException" to errorList))
        ctx.json(error).status(HttpStatus.UNAUTHORIZED_401)
    }
    this.exception(UnauthorizedResponse::class.java) { e, ctx ->
        logger.info("UnauthorizedResponse occurred for req -> ${ctx.url()}")
        val errorMessage = ctx.getI18n(e.message ?: I18nKeys.error_user_not_authenticated)
        val errorList = mutableListOf(errorMessage)
        if (e.localizedMessage != errorMessage) {
            errorList.add(e.localizedMessage)
        }
        val error = ResponseError(mapOf("Unauthorized" to errorList))
        ctx.json(error).status(HttpStatus.UNAUTHORIZED_401)
    }
    this.exception(ForbiddenResponse::class.java) { e, ctx ->
        logger.info("ForbiddenResponse occurred for req -> ${ctx.url()}")
        val errorMessage = ctx.getI18n(e.message ?: I18nKeys.error_user_not_authenticated)
        val errorList = mutableListOf(errorMessage)
        if (e.localizedMessage != errorMessage) {
            errorList.add(e.localizedMessage)
        }
        val error = ResponseError(mapOf("Forbidden" to errorList))
        ctx.json(error).status(HttpStatus.FORBIDDEN_403)
    }
    this.exception(BadRequestResponse::class.java) { e, ctx ->
        logger.info("BadRequestResponse occurred for req -> ${ctx.url()}")
        val errorMessage = ctx.getI18n(e.message ?: I18nKeys.error_bad_request)
        val errorList = mutableListOf(errorMessage)
        if (e.localizedMessage != errorMessage) {
            errorList.add(e.localizedMessage)
        }
        val error = ResponseError(mapOf("BadRequest" to errorList))
        ctx.json(error).status(HttpStatus.BAD_REQUEST_400)
    }
    this.exception(UnknownObjectException::class.java) { e, ctx ->
        logger.info("UnknownObjectException occurred for req -> ${ctx.url()}")
        val errorMessage = ctx.getI18n(e.message ?: I18nKeys.error_unknow_object_server_error)
        val errorList = mutableListOf(errorMessage)
        if (e.localizedMessage != errorMessage) {
            errorList.add(e.localizedMessage)
        }
        val error = ResponseError(mapOf("UnknownObject" to errorList))
        ctx.json(error).status(HttpStatus.UNPROCESSABLE_ENTITY_422)
    }
    this.exception(NotFoundResponse::class.java) { e, ctx ->
        logger.info("NotFoundResponse occurred for req -> ${ctx.url()}")
        val errorMessage = ctx.getI18n(e.message ?: I18nKeys.error_not_found_server_error)
        val errorList = mutableListOf(errorMessage)
        if (e.localizedMessage != errorMessage) {
            errorList.add(e.localizedMessage)
        }
        val error = ResponseError(mapOf("NotFound" to errorList))
        ctx.json(error).status(HttpStatus.NOT_FOUND_404)
    }
    this.exception(HttpResponseException::class.java) { e, ctx ->
        logger.info("HttpResponseException occurred for req -> ${ctx.url()}")
        val errorMessage = ctx.getI18n(e.message ?: I18nKeys.error_unknow_server_error)
        val errorList = mutableListOf(errorMessage)
        if (e.localizedMessage != errorMessage) {
            errorList.add(e.localizedMessage)
        }
        val errorMap = mutableMapOf(
            "ErrorResponse" to errorList,
            "Details" to e.details.map { it.key to listOf(it.value) }.flatMap { it.second })
        val error = ResponseError(errorMap)
        ctx.json(error).status(e.status)
    }
}

fun Context.getI18n(str: String) = I18nProvider.get(str, this)