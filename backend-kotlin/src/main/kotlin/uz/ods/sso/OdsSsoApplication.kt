package uz.ods.sso

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class OdsSsoApplication

fun main(args: Array<String>) {
    prewarmRuntimeClasses()
    runApplication<OdsSsoApplication>(*args)
}

internal fun prewarmRuntimeClasses(classLoader: ClassLoader = Thread.currentThread().contextClassLoader) {
    listOf(
        "com.fasterxml.jackson.databind.ObjectMapper",
        "com.fasterxml.jackson.databind.ObjectWriter",
        "org.springdoc.webmvc.ui.SwaggerIndexPageTransformer",
    ).forEach { Class.forName(it, true, classLoader) }
}
