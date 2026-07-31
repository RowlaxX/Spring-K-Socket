package fr.rowlaxx.springksocket

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["spring.main.allow-bean-definition-overriding=true"])
class SpringWebSocketAopApplicationTests {

    @Test
    fun contextLoads() {
    }

}
