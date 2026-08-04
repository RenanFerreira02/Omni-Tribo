package com.omnitribo.compartilhado.api;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class PingController {

  @GetMapping("/ping")
  PingResponse ping() {
    return new PingResponse("pong", Instant.now());
  }

  record PingResponse(String mensagem, Instant horario) {}
}
