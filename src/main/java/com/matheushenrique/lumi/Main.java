package com.matheushenrique.lumi;

import com.matheushenrique.lumi.business.*;
import com.matheushenrique.lumi.config.ConfiguracaoLocal;
import com.matheushenrique.lumi.controller.ChatController;
import com.matheushenrique.lumi.controller.LoginController;
import com.matheushenrique.lumi.controller.TemaController;
import com.matheushenrique.lumi.view.Componentes;
import com.matheushenrique.lumi.view.SplashView;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.io.InputStream;

/*
 * Parte JavaFX do app:
 * 1. abre a animação GSAP (SplashView)
 * 2. enquanto isso liga o Spring Boot e conecta no MongoDB Atlas
 * 3. quando os dois terminam, mostra a tela de login
 * Também faz a troca entre as telas (login, cadastro, chat...).
 */
public class Main extends Application {

    final TemaController temaController = new TemaController();

    private ConfigurableApplicationContext spring;
    private Stage janela;
    private Scene cena;
    private SplashView splash;

    // services (vêm do Spring)
    UsuarioService usuarioService;
    private ConversaService conversaService;
    private ChatService chatService;
    private RagService ragService;
    private LoginController loginController;

    private String erroAoIniciar;
    private boolean bancoPronto, animacaoTerminou, saidaIniciada, telaPrincipalAberta, splashFunciona = true;

    @Override
    public void start(Stage janelaPrincipal) {
        janela = janelaPrincipal;
        splash = new SplashView();
        colocarIcones(splash.getJanela());
        splash.carregar(new SplashView.Ponte(this::animacaoDeEntradaTerminou, this::abrirTelaPrincipal), () -> {
            splashFunciona = false; // se o WebView falhar, segue sem animação
            animacaoDeEntradaTerminou();
        });
        splash.getJanela().show();
        splash.mostrarStatus("Conectando ao MongoDB Atlas…", false);
        iniciarSpring();
        vigiarSplash();
    }

    // liga o Spring Boot e testa o MongoDB em segundo plano (enquanto a animação roda)
    private void iniciarSpring() {
        Componentes.emSegundoPlano(this::ligarSpring, ok -> {
            bancoPronto = true;
            splash.mostrarStatus("Tudo pronto!", false);
            tentarFecharSplash();
        }, erro -> {
            erroAoIniciar = erro;
            splash.mostrarStatus("Não foi possível conectar ao banco", true);
            tentarFecharSplash();
        });
    }

    private boolean ligarSpring() {
        ConfiguracaoLocal.carregar(); // lê %USERPROFILE%\.lumi\config.properties e confere a MONGODB_URI
        spring = new SpringApplicationBuilder(LumiApplication.class).headless(false).run();
        spring.getBean(MongoTemplate.class).executeCommand("{ ping: 1 }"); // testa a conexão
        usarServicos(spring.getBean(UsuarioService.class), spring.getBean(ConversaService.class),
                spring.getBean(ChatService.class), spring.getBean(RagService.class),
                spring.getBean(RecuperacaoSenhaService.class));
        ragService.cadastrarDocumentosIniciais();
        return true;
    }

    void usarServicos(UsuarioService usuarios, ConversaService conversas, ChatService chat, RagService rag,
                      RecuperacaoSenhaService recuperacao) {
        usuarioService = usuarios;
        conversaService = conversas;
        chatService = chat;
        ragService = rag;
        loginController = new LoginController(this, usuarioService, recuperacao, temaController);
    }

    private void animacaoDeEntradaTerminou() {
        animacaoTerminou = true;
        tentarFecharSplash();
    }

    // a splash só sai quando a animação acabou E o banco respondeu
    private void tentarFecharSplash() {
        if (!animacaoTerminou || !(bancoPronto || erroAoIniciar != null) || saidaIniciada) return;
        saidaIniciada = true;
        if (!splashFunciona) {
            abrirTelaPrincipal();
            return;
        }
        PauseTransition pausa = new PauseTransition(Duration.millis(erroAoIniciar == null ? 350 : 1200));
        pausa.setOnFinished(e -> splash.executar("playExit()")); // no fim o GSAP chama javaBridge.finish()
        pausa.play();
    }

    // segurança: se a animação travar, o app abre do mesmo jeito
    private void vigiarSplash() {
        PauseTransition espera = new PauseTransition(Duration.seconds(25));
        espera.setOnFinished(e -> {
            if (telaPrincipalAberta) return;
            if (bancoPronto || erroAoIniciar != null) abrirTelaPrincipal(); else vigiarSplash();
        });
        espera.play();
    }

    private void abrirTelaPrincipal() {
        if (telaPrincipalAberta || !(bancoPronto || erroAoIniciar != null)) return;
        telaPrincipalAberta = true;
        if (splash != null) splash.getJanela().close();
        cena = new Scene(new StackPane(), 1200, 780);
        temaController.aplicar(cena);
        janela.setScene(cena);
        janela.setTitle("Lumi");
        janela.setMinWidth(760);
        janela.setMinHeight(560);
        colocarIcones(janela);
        if (bancoPronto) mostrarLogin(null, null); else mostrarErroConexao();
        janela.centerOnScreen();
        janela.show();
    }

    // usado só pela pré-visualização das telas (testes)
    void abrirSemSplash(Stage janelaPrincipal) {
        janela = janelaPrincipal;
        bancoPronto = true;
        abrirTelaPrincipal();
    }

    // ---------- troca de telas ----------

    public void mostrarLogin(String email, String mensagem) {
        trocarTela(loginController.telaLogin(email, mensagem), "Entrar");
    }

    public void mostrarCadastro() {
        trocarTela(loginController.telaCadastro(), "Criar conta");
    }

    public void mostrarRecuperarSenha(String email) {
        trocarTela(loginController.telaRecuperarSenha(email), "Recuperar senha");
    }

    public void mostrarChat() {
        ChatController chat = new ChatController(this, usuarioService, conversaService, chatService, ragService, temaController);
        trocarTela(chat.getTela().getRaiz(), null);
    }

    // abre uma janela extra (ex.: base de conhecimento) com o mesmo tema
    public Stage abrirJanela(Parent conteudo, String titulo, double largura, double altura) {
        Stage nova = new Stage();
        nova.initOwner(janela);
        nova.initModality(Modality.WINDOW_MODAL);
        nova.setTitle(titulo + " - Lumi");
        nova.getIcons().setAll(janela.getIcons());
        Scene cenaNova = new Scene(conteudo, largura, altura);
        Runnable removerTema = temaController.aplicar(cenaNova);
        nova.setOnHidden(e -> removerTema.run());
        nova.setScene(cenaNova);
        return nova;
    }

    private void trocarTela(Parent tela, String subtitulo) {
        janela.setTitle(subtitulo == null ? "Lumi" : "Lumi - " + subtitulo);
        tela.setOpacity(0);
        cena.setRoot(tela);
        FadeTransition aparecer = new FadeTransition(Duration.millis(260), tela);
        aparecer.setToValue(1);
        aparecer.play();
    }

    // tela mostrada quando o MongoDB não responde ao abrir o app
    private void mostrarErroConexao() {
        Label mensagem = Componentes.texto(erroAoIniciar, "auth-subtitle");
        mensagem.setWrapText(true);
        Label ajuda = Componentes.texto("Arquivo de configuração: " + ConfiguracaoLocal.descricaoArquivo()
                + "\nConfira também sua internet e se o seu IP está liberado em Network Access no MongoDB Atlas.", "muted");
        ajuda.setWrapText(true);
        Componentes.BotaoCarregando tentarDeNovo = new Componentes.BotaoCarregando("Tentar novamente");
        tentarDeNovo.setMaxWidth(200);
        Button sair = new Button("Sair");
        sair.getStyleClass().add("secondary-button");
        sair.setOnAction(e -> Platform.exit());
        tentarDeNovo.setOnAction(e -> {
            tentarDeNovo.carregando("Conectando…");
            if (spring != null) spring.close();
            Componentes.emSegundoPlano(this::ligarSpring, ok -> {
                bancoPronto = true;
                mostrarLogin(null, null);
            }, erro -> {
                tentarDeNovo.pronto();
                mensagem.setText(erro);
            });
        });
        StackPane icone = new StackPane(Componentes.icone(Componentes.ALERTA, 28));
        icone.getStyleClass().add("error-icon");
        VBox cartao = new VBox(16, icone, Componentes.texto("Não foi possível iniciar", "auth-title"), mensagem, ajuda,
                new HBox(10, tentarDeNovo, sair));
        cartao.getStyleClass().add("auth-card");
        cartao.setMaxSize(520, VBox.USE_PREF_SIZE);
        StackPane tela = new StackPane(cartao);
        tela.getStyleClass().add("auth-root");
        tela.setPadding(new Insets(32));
        trocarTela(tela, "Erro de conexão");
    }

    private void colocarIcones(Stage alvo) {
        for (String tamanho : new String[]{"16", "32", "64", "256"}) {
            InputStream imagem = getClass().getResourceAsStream("/images/icon-" + tamanho + ".png");
            if (imagem != null) alvo.getIcons().add(new Image(imagem));
        }
    }

    @Override
    public void stop() {
        Componentes.encerrarTarefas();
        if (spring != null) spring.close();
    }
}
