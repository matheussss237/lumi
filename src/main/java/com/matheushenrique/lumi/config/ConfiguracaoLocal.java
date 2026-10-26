package com.matheushenrique.lumi.config;

import com.matheushenrique.lumi.exceptions.RegraNegocioException;
import com.mongodb.ConnectionString;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/*
 * Lê as chaves (MONGODB_URI, GROQ_API_KEY...) do arquivo %USERPROFILE%\.lumi\config.properties,
 * que fica FORA do projeto (a senha nunca vai para o GitHub).
 * Variável de ambiente sempre tem prioridade sobre o arquivo.
 */
public class ConfiguracaoLocal {

    public static final Path PASTA = Path.of(System.getProperty("user.home"), ".lumi");

    private ConfiguracaoLocal() {
    }

    public static void carregar() {
        Path arquivo = encontrarArquivo();
        if (arquivo != null) {
            Properties propriedades = new Properties();
            try (Reader leitor = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
                propriedades.load(leitor);
            } catch (IOException e) {
                throw new RegraNegocioException("Não foi possível ler o arquivo " + arquivo + ".", e);
            }
            for (String chave : propriedades.stringPropertyNames()) {
                String nome = chave.replace("﻿", "").trim(); // o Bloco de Notas às vezes coloca um caractere invisível no início
                String valor = limpar(propriedades.getProperty(chave));
                if (System.getenv(nome) == null && !valor.isEmpty()) {
                    System.setProperty(nome, valor);
                }
            }
        }
        conferirMongoUri(arquivo);
    }

    // usado na tela de erro para mostrar onde o arquivo foi procurado
    public static String descricaoArquivo() {
        Path arquivo = encontrarArquivo();
        return arquivo != null ? arquivo + " (encontrado)" : PASTA.resolve("config.properties") + " (NÃO encontrado)";
    }

    // aceita também o erro comum do Windows de salvar como "config.properties.txt"
    private static Path encontrarArquivo() {
        for (String nome : new String[]{"config.properties", "config.properties.txt", "config.txt"}) {
            Path arquivo = PASTA.resolve(nome);
            if (Files.isRegularFile(arquivo)) {
                return arquivo;
            }
        }
        return null;
    }

    // tira espaços e aspas que às vezes vêm junto quando a gente cola o texto
    private static String limpar(String valor) {
        String limpo = valor == null ? "" : valor.trim();
        if (limpo.length() >= 2 && (limpo.startsWith("\"") && limpo.endsWith("\"") || limpo.startsWith("'") && limpo.endsWith("'"))) {
            limpo = limpo.substring(1, limpo.length() - 1).trim();
        }
        return limpo;
    }

    // explica exatamente o que está errado com a MONGODB_URI
    private static void conferirMongoUri(Path arquivo) {
        String uri = System.getenv("MONGODB_URI");
        if (uri == null || uri.isBlank()) {
            uri = System.getProperty("MONGODB_URI", "");
        }
        uri = limpar(uri);
        if (uri.isEmpty()) {
            throw new RegraNegocioException(arquivo == null
                    ? "A MONGODB_URI não foi encontrada. Crie o arquivo " + PASTA.resolve("config.properties")
                      + " com a linha MONGODB_URI=mongodb+srv://..."
                    : "O arquivo " + arquivo + " foi encontrado, mas não tem a linha MONGODB_URI=mongodb+srv://...");
        }
        if (!uri.startsWith("mongodb+srv://") && !uri.startsWith("mongodb://")) {
            throw new RegraNegocioException("A MONGODB_URI precisa começar com mongodb+srv:// (sem aspas e sem espaços).");
        }
        if (uri.contains("<db_password>") || uri.contains("<") || uri.contains(">")) {
            throw new RegraNegocioException("Na MONGODB_URI, troque <db_password> pela senha do usuário do banco (sem os sinais < e >).");
        }
        if (uri.contains(" ")) {
            throw new RegraNegocioException("A MONGODB_URI tem um espaço no meio. Confira se o endereço está todo em uma linha só.");
        }
        try {
            new ConnectionString(uri); // mesma validação que o driver do MongoDB faz
        } catch (IllegalArgumentException e) {
            String motivo = String.valueOf(e.getMessage());
            if (motivo.contains("urlencoded") || motivo.contains("user information") || motivo.contains("password")) {
                throw new RegraNegocioException("A senha dentro da MONGODB_URI tem caractere especial (@ : / ? # %). "
                        + "No Atlas, em Database Access, troque a senha do usuário por uma só com letras e números.", e);
            }
            throw new RegraNegocioException("A MONGODB_URI está em formato inválido. Copie de novo o endereço do Atlas "
                    + "(Connect > Drivers > Java). Detalhe: " + motivo, e);
        }
        System.setProperty("MONGODB_URI", uri);
    }
}
