package com.matheushenrique.lumi.business;

import com.matheushenrique.lumi.exceptions.RegraNegocioException;
import com.matheushenrique.lumi.infrastructure.entitys.Mensagem;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// sobe uma "Groq" falsa local para testar o tratamento do erro 404 (modelo que saiu do ar)
class GroqServiceTest {

    private HttpServer servidor;
    private GroqService groq;
    private String modelos;

    @BeforeEach
    void subirGroqFalsa() throws IOException {
        modelos = "{\"data\":[{\"id\":\"whisper-large-v3\"},{\"id\":\"openai/gpt-oss-20b\"}]}";
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/openai/v1/chat/completions", troca -> {
            String corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (corpo.contains("\"modelo-velho\"")) {
                responder(troca, 404, "{\"error\":{\"message\":\"model not found\"}}");
            } else {
                responder(troca, 200, "{\"choices\":[{\"message\":{\"content\":\"Oi!\"}}]}");
            }
        });
        servidor.createContext("/openai/v1/models", troca -> responder(troca, 200, modelos));
        servidor.start();

        groq = new GroqService();
        ReflectionTestUtils.setField(groq, "url",
                "http://127.0.0.1:" + servidor.getAddress().getPort() + "/openai/v1/chat/completions");
        ReflectionTestUtils.setField(groq, "chaveApi", "gsk_teste");
        ReflectionTestUtils.setField(groq, "modelo", "modelo-velho");
    }

    @AfterEach
    void desligar() {
        servidor.stop(0);
    }

    @Test
    void trocaDeModeloQuandoOConfiguradoSaiuDoAr() {
        assertEquals("Oi!", groq.perguntar(List.of(Mensagem.builder().autor(Mensagem.Autor.USUARIO).conteudo("olá").build())));
        assertEquals("openai/gpt-oss-20b", ReflectionTestUtils.getField(groq, "modelo"));
    }

    @Test
    void explicaQuandoNaoHaOutroModelo() {
        modelos = "{\"data\":[{\"id\":\"whisper-large-v3\"}]}";
        RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                () -> groq.perguntar(List.of(Mensagem.builder().autor(Mensagem.Autor.USUARIO).conteudo("olá").build())));
        assertTrue(erro.getMessage().contains("GROQ_MODEL"));
    }

    private static void responder(com.sun.net.httpserver.HttpExchange troca, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        troca.sendResponseHeaders(status, bytes.length);
        troca.getResponseBody().write(bytes);
        troca.close();
    }
}
