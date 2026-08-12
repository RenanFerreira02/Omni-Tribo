package com.omnitribo;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(OperadorBancoTestConfig.class)
@ActiveProfiles("test")
public abstract class TesteIntegracaoBase extends ContainerConfig {}
