package com.shreyasnandurkar.botvinnikapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BotvinnikApiApplicationTests {

    @Test
    void contextLoads() {
    }

}
