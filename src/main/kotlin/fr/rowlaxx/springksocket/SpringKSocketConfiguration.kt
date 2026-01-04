package fr.rowlaxx.springksocket

import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackageClasses = [SpringKSocketConfiguration::class])
class SpringKSocketConfiguration {

    @PostConstruct
    fun postConstruct() {
        log.info("Initialized")
    }

}