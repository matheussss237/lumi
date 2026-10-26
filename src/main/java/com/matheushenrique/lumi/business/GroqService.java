package com.matheushenrique.lumi.business;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.matheushenrique.lumi.exceptions.RegraNegocioException;
import com.matheushenrique.lumi.infrastructure.entitys.Mensagem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// faz a chamada REST para a API da Groq (formato /chat/completions)
@Service
public class GroqService {

    @Value("${groq.url:https://api.groq.com/openai/v1/chat/completions}")
    private String url;

    @Value("${groq.api.key:}")
    private String chaveApi; // vem da variável de ambiente GROQ_API_KEY

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String modelo;

    // se o modelo configurado sair do ar (erro 404), tenta estes na ordem
    private static final List<String> MODELOS_RESERVA = List.of(
            "llama-3.3-70b-versatile", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.1-8b-instant");

    // modelos da Groq que não são de chat (áudio, moderação etc.)
    private static final Set<String> PALAVRAS_NAO_CHAT = Set.of("whisper", "guard", "tts", "playai", "orpheus", "prompt");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public boolean chaveConfigurada() {
        return chaveApi != null && !chaveApi.isBlank();
    }

    public String perguntar(List<Mensagem> mensagens) {
        if (!chaveConfigurada()) {
            throw new RegraNegocioException("A chave da Groq não foi configurada. Defina a variável GROQ_API_KEY.");
        }

        JsonArray lista = new JsonArray();
        for (Mensagem mensagem : mensagens) {
            JsonObject item = new JsonObject();
            item.addProperty("role", mensagem.getAutor().getValor());
            item.addProperty("content", mensagem.getConteudo());
            lista.add(item);
        }
        HttpResponse<String> resposta = enviar(lista);
        if (resposta.statusCode() == 404 && trocarModelo()) {
            resposta = enviar(lista);
        }
        return lerResposta(resposta);
    }

    private HttpResponse<String> enviar(JsonArray lista) {
        JsonObject corpo = new JsonObject();
        corpo.addProperty("model", modelo);
        corpo.add("messages", lista);
        corpo.addProperty("temperature", 0.5);
        corpo.addProperty("max_tokens", 1500);

        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + chaveApi)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo.toString()))
                .build();
        return chamar(requisicao);
    }

    // o modelo configurado não existe mais: pergunta à Groq quais existem e escolhe outro
    private boolean trocarModelo() {
        if (!url.endsWith("/chat/completions")) return false;
        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(url.replace("/chat/completions", "/models")))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + chaveApi)
                .GET()
                .build();
        HttpResponse<String> resposta = chamar(requisicao);
        if (resposta.statusCode() != 200) return false;

        List<String> disponiveis = new ArrayList<>();
        try {
            for (var item : JsonParser.parseString(resposta.body()).getAsJsonObject().getAsJsonArray("data")) {
                disponiveis.add(item.getAsJsonObject().get("id").getAsString());
            }
        } catch (RuntimeException e) {
            return false;
        }
        disponiveis.remove(modelo);

        String escolhido = MODELOS_RESERVA.stream().filter(disponiveis::contains).findFirst()
                .orElse(disponiveis.stream()
                        .filter(id -> PALAVRAS_NAO_CHAT.stream().noneMatch(id.toLowerCase()::contains))
                        .findFirst().orElse(null));
        if (escolhido == null) return false;
        System.out.println("[Lumi] O modelo " + modelo + " não está disponível na Groq. Usando " + escolhido + ".");
        modelo = escolhido;
        return true;
    }

    private HttpResponse<String> chamar(HttpRequest requisicao) {
        try {
            return http.send(requisicao, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException | UnknownHostException e) {
            throw new RegraNegocioException("Sem internet ou a API da Groq está fora do ar. Verifique sua rede.", e);
        } catch (HttpTimeoutException e) {
            throw new RegraNegocioException("A IA demorou demais para responder. Tente novamente.", e);
        } catch (IOException e) {
            throw new RegraNegocioException("Não foi possível falar com a API da Groq. Verifique sua internet.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RegraNegocioException("O envio foi interrompido.", e);
        }
    }

    private String lerResposta(HttpResponse<String> resposta) {
        int status = resposta.statusCode();
        if (status == 401 || status == 403) {
            throw new RegraNegocioException("A chave da Groq (GROQ_API_KEY) é inválida.");
        }
        if (status == 429) {
            throw new RegraNegocioException("Limite de uso da Groq atingido. Espere um pouco e tente de novo.");
        }
        if (status >= 500) {
            throw new RegraNegocioException("A API da Groq está fora do ar agora (erro " + status + "). Tente mais tarde.");
        }
        if (status == 404) {
            throw new RegraNegocioException("O modelo \"" + modelo + "\" não existe mais na Groq. "
                    + "Defina a variável GROQ_MODEL com um modelo da lista em console.groq.com/docs/models.");
        }
        if (status != 200) {
            throw new RegraNegocioException("A API da Groq recusou o pedido (erro " + status + "): " + motivo(resposta.body()));
        }
        try {
            String texto = JsonParser.parseString(resposta.body()).getAsJsonObject()
                    .getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString().trim();
            if (texto.isEmpty()) {
                throw new RegraNegocioException("A IA respondeu vazio. Tente perguntar de outro jeito.");
            }
            return texto;
        } catch (RuntimeException e) {
            if (e instanceof RegraNegocioException) throw e;
            throw new RegraNegocioException("A resposta da Groq veio em um formato inesperado.", e);
        }
    }

    // a Groq explica o erro em {"error": {"message": "..."}}
    private String motivo(String corpo) {
        try {
            return JsonParser.parseString(corpo).getAsJsonObject().getAsJsonObject("error").get("message").getAsString();
        } catch (RuntimeException e) {
            return "sem detalhes";
        }
    }
}
