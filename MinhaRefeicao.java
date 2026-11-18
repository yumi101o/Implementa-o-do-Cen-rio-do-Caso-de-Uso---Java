import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * MINHA REFEIÇÃO - Caso de uso "Assinar Plano de Refeições" (arquivo único).
 * Executar:  java MinhaRefeicao.java      (JDK 17+)
 * Persistência: pasta "dados/" (assinantes.csv e assinaturas.csv).
 * Dica para o vídeo: defina SMS_CODIGO_FIXO=123456 para fixar o código do SMS simulado.
 */
public class MinhaRefeicao {
    public static void main(String[] args) {
        PrintStream saida = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Scanner entrada = new Scanner(System.in, StandardCharsets.UTF_8);
        Path pasta = Path.of("dados");

        TelaAssinatura tela = new TelaAssinatura(entrada, saida);
        AssinaturaController controller = new AssinaturaController(
                tela,
                new ServicoSMS(saida),
                new Assinantes(pasta.resolve("assinantes.csv")),
                new CatalogoPlanos(),
                new Cardapio(),
                new ProcessadorPagamento(new OperadoraCartaoProxy()),
                new GeradorProtocolo(),
                new RepositorioAssinaturas(pasta.resolve("assinaturas.csv")));
        tela.setController(controller);
        try {
            tela.iniciar();
        } catch (CancelamentoException e) {
            saida.println("\nOperação cancelada. Até logo!");
        }
    }
}

/* ======================================================================
 *  ENTIDADES (DOMÍNIO)
 * ====================================================================== */

enum CategoriaPrato {
    PRATO_PRINCIPAL("Pratos principais"), ACOMPANHAMENTO("Acompanhamentos"), SOBREMESA("Sobremesas");
    private final String rotulo;
    CategoriaPrato(String rotulo) { this.rotulo = rotulo; }
    String getRotulo() { return rotulo; }
}

enum StatusAssinatura { CRIADA, AGUARDANDO_PAGAMENTO, ATIVA }

class Plano {
    private final String id, nome;
    private final int quantidadeRefeicoes;
    private final BigDecimal valor;
    Plano(String id, String nome, int quantidadeRefeicoes, String valor) {
        this.id = id; this.nome = nome; this.quantidadeRefeicoes = quantidadeRefeicoes; this.valor = new BigDecimal(valor);
    }
    String getId() { return id; }
    String getNome() { return nome; }
    int getQuantidadeRefeicoes() { return quantidadeRefeicoes; }
    BigDecimal getValor() { return valor; }
    @Override public String toString() { return nome + " - " + quantidadeRefeicoes + " refeições/semana - R$ " + valor; }
}

class PreferenciaAlimentar {
    private final Set<String> restricoes = new LinkedHashSet<>();
    PreferenciaAlimentar(Set<String> restricoes) {
        if (restricoes != null) for (String r : restricoes) this.restricoes.add(r.trim().toUpperCase());
    }
    Set<String> getRestricoes() { return restricoes; }
}

class Prato {
    private final String nome;
    private final CategoriaPrato categoria;
    private final Set<String> atende, planos;
    Prato(String nome, CategoriaPrato categoria, Set<String> atende, Set<String> planos) {
        this.nome = nome; this.categoria = categoria; this.atende = atende; this.planos = planos;
    }
    String getNome() { return nome; }
    CategoriaPrato getCategoria() { return categoria; }
    boolean disponivelNoPlano(Plano plano) { return planos.contains(plano.getId()); }
    boolean atende(PreferenciaAlimentar pref) { return atende.containsAll(pref.getRestricoes()); }
    @Override public String toString() { return nome + (atende.isEmpty() ? "" : " " + atende); }
}

class ItemPedido {
    private final Prato prato;
    private final CategoriaPrato categoria;
    ItemPedido(Prato prato, CategoriaPrato categoria) { this.prato = prato; this.categoria = categoria; }
    CategoriaPrato getCategoria() { return categoria; }
    @Override public String toString() { return categoria.getRotulo() + ": " + prato.getNome(); }
}

class Pedido {
    private final List<ItemPedido> itens = new ArrayList<>();
    private boolean aprovado;

    /** Retorna false se exceder o limite do plano (FA2). Substitui os itens da categoria. */
    boolean adicionarItens(List<Prato> selecionados, CategoriaPrato categoria, int limite) {
        if (selecionados.size() > limite) return false;
        itens.removeIf(i -> i.getCategoria() == categoria);
        for (Prato p : selecionados) itens.add(new ItemPedido(p, categoria));
        return true;
    }
    List<ItemPedido> obterComposicao() { return itens; }
    void aprovar() { aprovado = true; }
    boolean isAprovado() { return aprovado; }
}

class Endereco {
    private final String logradouro, numero, bairro, cidade, cep;
    Endereco(String logradouro, String numero, String bairro, String cidade, String cep) {
        this.logradouro = logradouro; this.numero = numero; this.bairro = bairro; this.cidade = cidade; this.cep = cep;
    }
    @Override public String toString() { return logradouro + ", " + numero + " - " + bairro + " - " + cidade + " - CEP " + cep; }
}

class CartaoDeCredito {
    private final String numero, titular, validade, cvv;
    CartaoDeCredito(String numero, String titular, String validade, String cvv) {
        this.numero = numero.replaceAll("\\D", ""); this.titular = titular; this.validade = validade; this.cvv = cvv;
    }
    String getNumero() { return numero; }
    String getTitular() { return titular; }
    String getValidade() { return validade; }
    String getCvv() { return cvv; }
    String getFinal() { return numero.length() >= 4 ? numero.substring(numero.length() - 4) : numero; }
    String getMascarado() { return "**** **** **** " + getFinal(); }
}

class ResultadoAutorizacao {
    private final boolean autorizado;
    private final String mensagem;
    ResultadoAutorizacao(boolean autorizado, String mensagem) { this.autorizado = autorizado; this.mensagem = mensagem; }
    boolean isAutorizado() { return autorizado; }
    String getMensagem() { return mensagem; }
}

class Pagamento {
    private final BigDecimal valor;
    private final ResultadoAutorizacao resultado;
    private final String cartaoMascarado;
    Pagamento(BigDecimal valor, ResultadoAutorizacao resultado, CartaoDeCredito cartao) {
        this.valor = valor; this.resultado = resultado; this.cartaoMascarado = cartao.getMascarado();
    }
    BigDecimal getValor() { return valor; }
    boolean isAutorizado() { return resultado.isAutorizado(); }
    String getCartaoMascarado() { return cartaoMascarado; }
}

class Assinante {
    private final String celular;
    private final List<String> cartoes = new ArrayList<>();   // "TITULAR:1234"
    Assinante(String celular) { this.celular = celular; }
    String getCelular() { return celular; }
    List<String> getCartoes() { return cartoes; }
    void adicionarCartao(CartaoDeCredito cartao) { carregarCartao(cartao.getTitular() + ":" + cartao.getFinal()); }
    void carregarCartao(String ref) { if (!ref.isBlank() && !cartoes.contains(ref)) cartoes.add(ref); }
}

/** Entidade central: o Controller só fala com ela, que aciona Plano, Pedido e Pagamento (Lei de Demeter). */
class Assinatura {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final Assinante assinante;
    private final Plano plano;
    private final Pedido pedido = new Pedido();
    private final List<Pagamento> pagamentos = new ArrayList<>();
    private PreferenciaAlimentar preferencias = new PreferenciaAlimentar(null);
    private Endereco enderecoEntrega;
    private StatusAssinatura status = StatusAssinatura.CRIADA;
    private String protocolo;

    Assinatura(Assinante assinante, Plano plano) { this.assinante = assinante; this.plano = plano; }   // create(assinante, plano)

    void definirPreferencias(PreferenciaAlimentar p) { preferencias = p; }
    boolean adicionarItensAoPedido(List<Prato> selecionados, CategoriaPrato categoria) {
        return pedido.adicionarItens(selecionados, categoria, plano.getQuantidadeRefeicoes());
    }
    List<ItemPedido> obterItensPedido() { return pedido.obterComposicao(); }
    void definirEnderecoEntrega(Endereco e) { enderecoEntrega = e; }
    void alterarStatus(StatusAssinatura s) { status = s; }
    BigDecimal calcularValorTotal() { return plano.getValor(); }
    void registrarPagamento(ResultadoAutorizacao resultado, BigDecimal valorTotal, CartaoDeCredito cartao) {
        pagamentos.add(new Pagamento(valorTotal, resultado, cartao));
        if (resultado.isAutorizado()) assinante.adicionarCartao(cartao);
    }
    void ativar() { status = StatusAssinatura.ATIVA; pedido.aprovar(); }
    void definirProtocolo(String p) { protocolo = p; }

    String getId() { return id; }
    Assinante getAssinante() { return assinante; }
    Plano getPlano() { return plano; }
    PreferenciaAlimentar getPreferencias() { return preferencias; }
    Endereco getEnderecoEntrega() { return enderecoEntrega; }
    StatusAssinatura getStatus() { return status; }
    String getProtocolo() { return protocolo; }
    List<Pagamento> getPagamentos() { return pagamentos; }
}

/* ======================================================================
 *  COLEÇÕES E PERSISTÊNCIA (CSV)
 * ====================================================================== */

class Csv {
    static List<String[]> ler(Path arquivo) {
        List<String[]> linhas = new ArrayList<>();
        try {
            if (!Files.exists(arquivo)) return linhas;
            List<String> todas = Files.readAllLines(arquivo, StandardCharsets.UTF_8);
            for (int i = 1; i < todas.size(); i++) if (!todas.get(i).isBlank()) linhas.add(todas.get(i).split(";", -1));
        } catch (Exception e) { throw new RuntimeException("Erro ao ler " + arquivo, e); }
        return linhas;
    }
    static void escrever(Path arquivo, String cabecalho, List<String> linhas) {
        try {
            Files.createDirectories(arquivo.toAbsolutePath().getParent());
            List<String> saida = new ArrayList<>();
            saida.add(cabecalho);
            saida.addAll(linhas);
            Files.write(arquivo, saida, StandardCharsets.UTF_8);
        } catch (Exception e) { throw new RuntimeException("Erro ao gravar " + arquivo, e); }
    }
    static String limpar(String s) { return s == null ? "" : s.replace(";", ",").replace("\n", " "); }
}

class CatalogoPlanos {
    private final List<Plano> planos = List.of(
            new Plano("P1", "Plano Essencial", 3, "149.90"),
            new Plano("P2", "Plano Completo", 5, "229.90"),
            new Plano("P3", "Plano Premium", 7, "319.90"));
    List<Plano> listarPlanos() { return planos; }
}

class Cardapio {
    private final List<Prato> pratos = new ArrayList<>();
    Cardapio() {
        CategoriaPrato pp = CategoriaPrato.PRATO_PRINCIPAL, ac = CategoriaPrato.ACOMPANHAMENTO, so = CategoriaPrato.SOBREMESA;
        add("Frango grelhado com ervas", pp, "SEM_LACTOSE|SEM_GLUTEN", "P1|P2|P3");
        add("Lasanha de berinjela", pp, "VEGETARIANO", "P1|P2|P3");
        add("Strogonoff de carne", pp, "", "P1|P2|P3");
        add("Risoto de cogumelos", pp, "VEGETARIANO|SEM_GLUTEN", "P2|P3");
        add("Salmão ao molho de limão", pp, "SEM_LACTOSE|SEM_GLUTEN", "P2|P3");
        add("Escondidinho de mandioca com carne seca", pp, "SEM_GLUTEN", "P1|P2|P3");
        add("Moqueca de palmito", pp, "VEGETARIANO|SEM_LACTOSE|SEM_GLUTEN", "P1|P2|P3");
        add("Arroz integral", ac, "VEGETARIANO|SEM_LACTOSE|SEM_GLUTEN", "P1|P2|P3");
        add("Purê de batata", ac, "VEGETARIANO|SEM_GLUTEN", "P1|P2|P3");
        add("Legumes salteados", ac, "VEGETARIANO|SEM_LACTOSE|SEM_GLUTEN", "P1|P2|P3");
        add("Farofa de banana", ac, "VEGETARIANO|SEM_LACTOSE", "P2|P3");
        add("Mousse de maracujá", so, "VEGETARIANO|SEM_GLUTEN", "P1|P2|P3");
        add("Salada de frutas", so, "VEGETARIANO|SEM_LACTOSE|SEM_GLUTEN", "P1|P2|P3");
        add("Brownie de chocolate", so, "VEGETARIANO", "P2|P3");
    }
    private void add(String nome, CategoriaPrato cat, String atende, String planos) {
        pratos.add(new Prato(nome, cat, conjunto(atende), conjunto(planos)));
    }
    private static Set<String> conjunto(String s) {
        Set<String> r = new LinkedHashSet<>();
        if (!s.isBlank()) r.addAll(Arrays.asList(s.split("\\|")));
        return r;
    }
    List<Prato> buscarPratos(CategoriaPrato categoria, Plano plano, PreferenciaAlimentar pref) {
        List<Prato> r = new ArrayList<>();
        for (Prato p : pratos)
            if (p.getCategoria() == categoria && p.disponivelNoPlano(plano) && p.atende(pref)) r.add(p);
        return r;
    }
}

class Assinantes {
    private final Path arquivo;
    private final Map<String, Assinante> porCelular = new LinkedHashMap<>();
    Assinantes(Path arquivo) {
        this.arquivo = arquivo;
        for (String[] c : Csv.ler(arquivo)) {
            Assinante a = new Assinante(c[0]);
            if (c.length > 1 && !c[1].isBlank()) for (String ref : c[1].split("\\|")) a.carregarCartao(ref);
            porCelular.put(a.getCelular(), a);
        }
    }
    Assinante obterOuCadastrar(String celular) {
        Assinante a = porCelular.get(celular);
        if (a == null) { a = new Assinante(celular); porCelular.put(celular, a); salvar(); }
        return a;
    }
    void salvar() {
        List<String> linhas = new ArrayList<>();
        for (Assinante a : porCelular.values()) linhas.add(a.getCelular() + ";" + Csv.limpar(String.join("|", a.getCartoes())));
        Csv.escrever(arquivo, "celular;cartoes", linhas);
    }
}

class RepositorioAssinaturas {
    private static final String CABECALHO = "id;celular;plano;status;preferencias;endereco;itens;valorTotal;pagamentos;protocolo";
    private final Path arquivo;
    RepositorioAssinaturas(Path arquivo) { this.arquivo = arquivo; }

    void salvar(Assinatura a) {
        List<String> itens = new ArrayList<>();
        for (ItemPedido i : a.obterItensPedido()) itens.add(i.toString());
        List<String> pagamentos = new ArrayList<>();
        for (Pagamento p : a.getPagamentos())
            pagamentos.add((p.isAutorizado() ? "AUTORIZADO" : "NEGADO") + ":" + p.getCartaoMascarado() + ":" + p.getValor());

        String nova = String.join(";", a.getId(), a.getAssinante().getCelular(), a.getPlano().getId(), a.getStatus().name(),
                String.join("|", a.getPreferencias().getRestricoes()),
                Csv.limpar(a.getEnderecoEntrega() == null ? "" : a.getEnderecoEntrega().toString()),
                Csv.limpar(String.join("|", itens)), a.calcularValorTotal().toString(),
                Csv.limpar(String.join("|", pagamentos)), a.getProtocolo() == null ? "" : a.getProtocolo());

        List<String> linhas = new ArrayList<>();
        boolean achou = false;
        for (String[] c : Csv.ler(arquivo)) {
            if (c[0].equals(a.getId())) { linhas.add(nova); achou = true; }
            else linhas.add(String.join(";", c));
        }
        if (!achou) linhas.add(nova);
        Csv.escrever(arquivo, CABECALHO, linhas);
    }
}

/* ======================================================================
 *  SERVIÇOS (ATORES SECUNDÁRIOS)
 * ====================================================================== */

class ServicoSMS {
    private final PrintStream saida;
    private final Random random = new Random();
    private final Map<String, String> codigos = new HashMap<>();
    private final Map<String, Integer> erros = new HashMap<>();
    ServicoSMS(PrintStream saida) { this.saida = saida; }

    void enviarCodigo(String celular) {
        String codigo = System.getenv("SMS_CODIGO_FIXO");
        if (codigo == null || codigo.isBlank()) codigo = String.format("%06d", random.nextInt(1000000));
        codigos.put(celular, codigo);
        erros.put(celular, 0);
        saida.println("   [SMS simulado] Para " + celular + ": seu código Minha Refeição é " + codigo);
    }
    boolean validar(String celular, String codigo) {
        if (estaBloqueado(celular)) return false;
        if (codigo.equals(codigos.get(celular))) return true;
        erros.merge(celular, 1, Integer::sum);
        return false;
    }
    boolean estaBloqueado(String celular) { return erros.getOrDefault(celular, 0) >= 3; }
}

/** Simula a operadora: recusa número != 16 dígitos, CVV != 3 dígitos, cartão vencido e finais 0000. */
class OperadoraCartaoProxy {
    ResultadoAutorizacao solicitarAutorizacao(CartaoDeCredito c, BigDecimal valor) {
        if (!c.getNumero().matches("\\d{16}")) return new ResultadoAutorizacao(false, "Número de cartão inválido");
        if (!c.getCvv().matches("\\d{3}")) return new ResultadoAutorizacao(false, "CVV inválido");
        if (!validadeOk(c.getValidade())) return new ResultadoAutorizacao(false, "Cartão vencido ou validade inválida");
        if (c.getNumero().endsWith("0000")) return new ResultadoAutorizacao(false, "Limite insuficiente");
        return new ResultadoAutorizacao(true, "Autorizado");
    }
    private boolean validadeOk(String v) {
        try {
            String[] p = v.split("/");
            return !YearMonth.of(Integer.parseInt(p[1]), Integer.parseInt(p[0])).isBefore(YearMonth.now());
        } catch (Exception e) { return false; }
    }
}

class ProcessadorPagamento {
    private final OperadoraCartaoProxy operadora;
    ProcessadorPagamento(OperadoraCartaoProxy operadora) { this.operadora = operadora; }
    ResultadoAutorizacao autorizar(CartaoDeCredito cartao, BigDecimal valor) { return operadora.solicitarAutorizacao(cartao, valor); }
}

class GeradorProtocolo {
    private final Random random = new Random();
    String gerar() {
        return "MR-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%05d", random.nextInt(100000));
    }
}

/* ======================================================================
 *  CONTROLE (GRASP Controller) - cada método = mensagem do diagrama de sequência
 * ====================================================================== */

class AssinaturaController {
    private static final CategoriaPrato[] CATEGORIAS = CategoriaPrato.values();
    private final TelaAssinatura tela;
    private final ServicoSMS sms;
    private final Assinantes assinantes;
    private final CatalogoPlanos catalogo;
    private final Cardapio cardapio;
    private final ProcessadorPagamento pagamento;
    private final GeradorProtocolo geradorProtocolo;
    private final RepositorioAssinaturas repositorio;

    private String celular;
    private Assinante assinante;
    private Assinatura assinatura;
    private BigDecimal valorTotal;

    AssinaturaController(TelaAssinatura tela, ServicoSMS sms, Assinantes assinantes, CatalogoPlanos catalogo, Cardapio cardapio,
                         ProcessadorPagamento pagamento, GeradorProtocolo geradorProtocolo, RepositorioAssinaturas repositorio) {
        this.tela = tela; this.sms = sms; this.assinantes = assinantes; this.catalogo = catalogo; this.cardapio = cardapio;
        this.pagamento = pagamento; this.geradorProtocolo = geradorProtocolo; this.repositorio = repositorio;
    }

    // Passos 1 a 4
    void iniciarAssinatura(String celular) {
        this.celular = celular;
        sms.enviarCodigo(celular);
        tela.solicitarCodigo();
    }

    void validarCodigo(String codigo) {
        if (!sms.validar(celular, codigo)) {                                   // FA1
            tela.exibirErro("código inválido");
            if (sms.estaBloqueado(celular)) {
                tela.exibirErro("número máximo de tentativas atingido; um novo código foi enviado");
                sms.enviarCodigo(celular);
            }
            tela.solicitarCodigo();
            return;
        }
        assinante = assinantes.obterOuCadastrar(celular);
        tela.exibirPlanos(catalogo.listarPlanos());                            // passos 5 e 6
    }

    void confirmarPlano(Plano plano) {
        assinatura = new Assinatura(assinante, plano);
        tela.solicitarPreferencias();                                          // passo 7
    }

    // Passos 7 a 9
    void registrarPreferencias(PreferenciaAlimentar preferencias) {
        assinatura.definirPreferencias(preferencias);
        apresentarCategoria(0);
    }

    // Passos 10 a 18 (loop sobre as categorias)
    private void apresentarCategoria(int indice) {
        if (indice >= CATEGORIAS.length) { tela.exibirResumoPedido(assinatura.obterItensPedido()); return; }   // passo 19
        CategoriaPrato categoria = CATEGORIAS[indice];
        tela.exibirPratos(cardapio.buscarPratos(categoria, assinatura.getPlano(), assinatura.getPreferencias()), categoria);
    }

    void adicionarItens(List<Prato> selecionados, CategoriaPrato categoria) {
        if (!assinatura.adicionarItensAoPedido(selecionados, categoria)) {     // FA2
            tela.exibirErro("limite de refeições do plano excedido (máximo " + assinatura.getPlano().getQuantidadeRefeicoes() + " por categoria)");
            apresentarCategoria(categoria.ordinal());
            return;
        }
        apresentarCategoria(categoria.ordinal() + 1);
    }

    // Passos 20 a 24
    void registrarEndereco(Endereco endereco) {
        assinatura.definirEnderecoEntrega(endereco);
        assinatura.alterarStatus(StatusAssinatura.AGUARDANDO_PAGAMENTO);
        valorTotal = assinatura.calcularValorTotal();
        repositorio.salvar(assinatura);
        tela.exibirPagamento(valorTotal);
    }

    // Passos 25 a 32
    void processarPagamento(CartaoDeCredito cartao) {
        ResultadoAutorizacao resultado = pagamento.autorizar(cartao, valorTotal);
        assinatura.registrarPagamento(resultado, valorTotal, cartao);
        assinantes.salvar();

        if (!resultado.isAutorizado()) {                                       // FA3
            repositorio.salvar(assinatura);
            tela.exibirErro("pagamento não autorizado (" + resultado.getMensagem() + ")");
            tela.solicitarOutroCartao(valorTotal);
            return;
        }
        assinatura.ativar();
        assinatura.definirProtocolo(geradorProtocolo.gerar());
        repositorio.salvar(assinatura);
        tela.exibirConfirmacao(assinatura);
    }
}

/* ======================================================================
 *  FRONTEIRA (console)  -  digite "sair" em qualquer pergunta para cancelar
 * ====================================================================== */

class CancelamentoException extends RuntimeException {
    CancelamentoException() { super("Operação cancelada"); }
}

class TelaAssinatura {
    private final Scanner entrada;
    private final PrintStream saida;
    private AssinaturaController controller;

    TelaAssinatura(Scanner entrada, PrintStream saida) { this.entrada = entrada; this.saida = saida; }
    void setController(AssinaturaController c) { controller = c; }

    void iniciar() {
        saida.println("==============================================");
        saida.println("   MINHA REFEIÇÃO - Assinar Plano de Refeições");
        saida.println("   (digite 'sair' a qualquer momento)");
        saida.println("==============================================");
        String celular;
        do {
            celular = ler("Informe seu celular (DDD + número): ").replaceAll("\\D", "");
            if (!celular.matches("\\d{10,11}")) saida.println("   ! Celular inválido. Use 10 ou 11 dígitos.");
        } while (!celular.matches("\\d{10,11}"));
        controller.iniciarAssinatura(celular);
    }

    void solicitarCodigo() { controller.validarCodigo(ler("Informe o código recebido por SMS: ")); }

    void exibirPlanos(List<Plano> planos) {
        saida.println("\n--- PLANOS DISPONÍVEIS ---");
        for (int i = 0; i < planos.size(); i++) saida.println("  " + (i + 1) + ") " + planos.get(i));
        int op = lerInteiro("Escolha o plano (número): ", planos.size());
        controller.confirmarPlano(planos.get(op - 1));
    }

    void solicitarPreferencias() {
        saida.println("\n--- PREFERÊNCIAS ALIMENTARES ---");
        saida.println("  Opções: VEGETARIANO, SEM_LACTOSE, SEM_GLUTEN (separe por vírgula; Enter = nenhuma)");
        String linha = ler("Preferências: ");
        Set<String> restricoes = new LinkedHashSet<>();
        if (!linha.isBlank()) for (String r : linha.split(",")) if (!r.isBlank()) restricoes.add(r.trim());
        controller.registrarPreferencias(new PreferenciaAlimentar(restricoes));
    }

    void exibirPratos(List<Prato> pratos, CategoriaPrato categoria) {
        saida.println("\n--- " + categoria.getRotulo().toUpperCase() + " ---");
        if (pratos.isEmpty()) {
            saida.println("  (nenhuma opção disponível para seu plano/preferências)");
            controller.adicionarItens(new ArrayList<>(), categoria);
            return;
        }
        for (int i = 0; i < pratos.size(); i++) saida.println("  " + (i + 1) + ") " + pratos.get(i));
        boolean obrigatorio = categoria == CategoriaPrato.PRATO_PRINCIPAL;
        List<Prato> selecionados = null;
        while (selecionados == null) {
            String linha = ler("Escolha os números separados por vírgula" + (obrigatorio ? " (mín. 1)" : " (Enter = nenhum)") + ": ");
            selecionados = converter(linha, pratos, obrigatorio);
            if (selecionados == null) saida.println("   ! Seleção inválida.");
        }
        controller.adicionarItens(selecionados, categoria);
    }

    private List<Prato> converter(String linha, List<Prato> pratos, boolean obrigatorio) {
        Set<Integer> indices = new LinkedHashSet<>();
        if (!linha.isBlank()) {
            for (String t : linha.split(",")) {
                try {
                    int n = Integer.parseInt(t.trim());
                    if (n < 1 || n > pratos.size()) return null;
                    indices.add(n);
                } catch (NumberFormatException e) { return null; }
            }
        }
        if (obrigatorio && indices.isEmpty()) return null;
        List<Prato> sel = new ArrayList<>();
        for (int n : indices) sel.add(pratos.get(n - 1));
        return sel;
    }

    void exibirResumoPedido(List<ItemPedido> itens) {
        saida.println("\n--- RESUMO DO PEDIDO ---");
        for (ItemPedido i : itens) saida.println("  - " + i);
        saida.println("\n--- ENDEREÇO DE ENTREGA ---");
        String logradouro = lerObrigatorio("Logradouro: ");
        String numero = lerObrigatorio("Número: ");
        String bairro = lerObrigatorio("Bairro: ");
        String cidade = lerObrigatorio("Cidade: ");
        String cep = lerObrigatorio("CEP: ");
        controller.registrarEndereco(new Endereco(logradouro, numero, bairro, cidade, cep));
    }

    void exibirPagamento(BigDecimal valorTotal) {
        saida.println("\n--- PAGAMENTO ---");
        saida.println("  Valor total: R$ " + valorTotal);
        String numero = lerObrigatorio("Número do cartão (16 dígitos): ");
        String titular = lerObrigatorio("Nome do titular: ");
        String validade = lerObrigatorio("Validade (MM/AAAA): ");
        String cvv = lerObrigatorio("CVV: ");
        controller.processarPagamento(new CartaoDeCredito(numero, titular, validade, cvv));
    }

    void solicitarOutroCartao(BigDecimal valorTotal) {                           // FA3
        if (ler("Deseja tentar com outro cartão? (s/n): ").equalsIgnoreCase("s")) exibirPagamento(valorTotal);
        else saida.println("\nProcesso encerrado. Sua assinatura permanece AGUARDANDO_PAGAMENTO.");
    }

    void exibirConfirmacao(Assinatura a) {
        saida.println("\n==============================================");
        saida.println("   ASSINATURA CONFIRMADA!");
        saida.println("==============================================");
        saida.println("  Protocolo : " + a.getProtocolo());
        saida.println("  Plano     : " + a.getPlano().getNome());
        saida.println("  Status    : " + a.getStatus());
        saida.println("  Entrega   : " + a.getEnderecoEntrega());
        saida.println("  Itens     : " + a.obterItensPedido().size());
        saida.println("  Valor     : R$ " + a.calcularValorTotal());
    }

    void exibirErro(String mensagem) { saida.println("   ! Erro: " + mensagem); }

    // ---- leitura ----
    private String ler(String prompt) {
        saida.print(prompt);
        saida.flush();
        if (!entrada.hasNextLine()) throw new CancelamentoException();
        String linha = entrada.nextLine().trim();
        if (linha.equalsIgnoreCase("sair")) throw new CancelamentoException();
        saida.println();
        return linha;
    }
    private String lerObrigatorio(String prompt) {
        String v;
        do { v = ler(prompt); } while (v.isBlank());
        return v;
    }
    private int lerInteiro(String prompt, int max) {
        while (true) {
            try {
                int n = Integer.parseInt(ler(prompt));
                if (n >= 1 && n <= max) return n;
            } catch (NumberFormatException ignored) { }
            saida.println("   ! Opção inválida.");
        }
    }
}
