package com.matheushenrique.lumi.exceptions;

import com.mongodb.MongoException;
import com.mongodb.MongoSecurityException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.util.logging.Level;
import java.util.logging.Logger;

// transforma qualquer erro em uma mensagem que o usuário entende (sem stack trace na tela)
public class GlobalExceptionHandler {

    private static final Logger LOG = Logger.getLogger("Lumi");

    private GlobalExceptionHandler() {
    }

    public static String mensagem(Throwable ex) {
        for (Throwable erro = ex; erro != null; erro = erro.getCause()) {
            if (erro instanceof RegraNegocioException || erro instanceof ResourceNotFoundException) {
                return erro.getMessage();
            }
            if (erro instanceof DuplicateKeyException) {
                return "Já existe um cadastro com esses dados.";
            }
            if (erro instanceof IllegalArgumentException && String.valueOf(erro.getMessage()).contains("connection string")) {
                return "A variável MONGODB_URI não foi configurada ou está inválida. Veja o README.";
            }
            if (erro instanceof MongoException || erro instanceof DataAccessException) {
                LOG.log(Level.WARNING, "Erro no MongoDB", ex);
                return mensagemMongo(ex);
            }
        }
        LOG.log(Level.SEVERE, "Erro inesperado", ex);
        return "Ocorreu um erro inesperado. Tente novamente.";
    }

    // descobre o motivo do erro de conexão olhando o texto de todas as causas
    private static String mensagemMongo(Throwable ex) {
        StringBuilder tudo = new StringBuilder();
        boolean senhaErrada = false;
        for (Throwable erro = ex; erro != null; erro = erro.getCause()) {
            tudo.append(erro.getClass().getSimpleName()).append(' ').append(erro.getMessage()).append('\n');
            senhaErrada |= erro instanceof MongoSecurityException;
        }
        String texto = tudo.toString();

        if (senhaErrada || texto.contains("Authentication failed") || texto.contains("bad auth")) {
            return "O MongoDB Atlas recusou o usuário ou a senha. Confira a senha dentro da MONGODB_URI "
                    + "(em Database Access dá para criar uma senha nova).";
        }
        if (texto.contains("SRV") || texto.contains("UnknownHost") || texto.contains("DNS")) {
            return "Esta rede não conseguiu encontrar o endereço do Atlas (DNS). Teste em outra rede, "
                    + "por exemplo o 4G do celular.";
        }
        if (texto.contains("SSL") || texto.contains("PKIX") || texto.contains("handshake")
                || texto.contains("end of stream") || texto.contains("Connection reset")) {
            return "O Atlas fechou a conexão. Quase sempre é o IP deste computador que não está liberado: "
                    + "no Atlas, vá em Network Access > Add IP Address > Allow Access from Anywhere (0.0.0.0/0) "
                    + "e espere 1 minuto. Se continuar, a rede pode estar bloqueando: teste com o 4G do celular.";
        }
        if (texto.contains("Timed out") || texto.contains("timed out") || texto.contains("Connection refused")) {
            return "Não foi possível chegar ao Atlas (tempo esgotado). O IP deste computador pode não estar liberado "
                    + "em Network Access, ou a rede (escola/empresa) bloqueia a porta 27017. "
                    + "Teste liberar 0.0.0.0/0 no Atlas ou usar o 4G do celular.";
        }
        String primeira = texto.lines().findFirst().orElse("");
        return "Não foi possível falar com o MongoDB Atlas. Detalhe: "
                + (primeira.length() > 200 ? primeira.substring(0, 200) + "…" : primeira);
    }
}
