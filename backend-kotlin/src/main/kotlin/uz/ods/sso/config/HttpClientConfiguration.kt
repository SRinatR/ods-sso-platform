package uz.ods.sso.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration(proxyBeanMethods = false)
class HttpClientConfiguration {
    @Bean(destroyMethod = "close")
    fun externalHttpExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    @Bean
    fun criticalExternalHttpClient(externalHttpExecutor: ExecutorService): HttpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_3)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(externalHttpExecutor)
            .build()
}
