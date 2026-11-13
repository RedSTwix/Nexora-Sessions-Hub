# Nexora Sessions Hub

Aplicativo Android para organizar várias contas em sessões WebView isoladas. Cada conta mantém
cookies, cache e armazenamento web próprios, permitindo alternar entre perfis sem misturar logins.

> O Nexora Sessions Hub trabalha somente com dados locais do Android WebView. O aplicativo não
> envia, sincroniza ou exporta credenciais.

## Recursos principais

- **Sessões isoladas** — cada conta utiliza um perfil independente do Android System WebView.
- **Identidade dos novos perfis** — proteção parcial contra fingerprinting, gerada somente ao criar uma conta.
- **Pastas de contas** — organize, renomeie, personalize e reordene pastas e contas.
- **Seleção múltipla** — mova várias contas ou reorganize grupos preservando a ordem relativa.
- **Indicadores de disponibilidade** — marque contas utilizadas por um período configurável.
- **Autenticação direcionada** — escolha a pasta e a conta quando outro aplicativo abrir um login.
- **Arquivos nos sites** — selecione arquivos pelo seletor do Android, incluindo seleção múltipla quando solicitada pelo site.
- **Atualizações pelo app** — detecte releases oficiais, baixe com progresso e confirme a instalação no Android.
- **Privacidade local** — sem servidor próprio, telemetria ou sincronização externa.

## Como funciona

1. Crie uma pasta.
2. Adicione uma conta e faça o login manualmente no navegador isolado.
3. Repita o processo para os demais perfis.
4. Ao receber uma solicitação externa de autenticação, escolha o Nexora Sessions Hub.
5. Selecione primeiro a pasta e depois a conta que receberá a URL.

Pastas e contas podem ser renomeadas ou reordenadas sem perder suas sessões. Mover uma conta para
outra pasta também não altera os dados do perfil WebView.

## Disponibilidade

O controle de disponibilidade ajuda a espaçar o uso das contas:

- ao abrir uma conta controlada, o indicador fica vermelho pelo período configurado;
- terminado o período, o indicador volta a ficar verde;
- uma pasta fica vermelha quando todas as suas contas controladas estão indisponíveis;
- **Liberar todos agora** redefine somente os indicadores, sem apagar logins, cookies ou sessões.

## Criar pastas e contas

O card com o símbolo **+** ocupa toda a largura da tela e acompanha o contexto: na tela inicial,
**Criar pasta** organiza um novo grupo; dentro de uma pasta, **Adicionar conta** cria uma sessão
isolada que abre no Google. O aplicativo não utiliza serviços de Acessibilidade.

## Privacidade e dados locais

Os sites podem solicitar arquivos pelo seletor do Android. Somente os arquivos escolhidos são
entregues à página; cancelar a seleção não envia arquivos. O app não pede acesso geral ao armazenamento.

Contas criadas a partir da versão 0.7.0 recebem uma identidade aleatória persistente. Ela é mantida
ao reabrir, renomear ou mover a conta. Contas anteriores continuam sem essa proteção adicional:
abrir ou editar uma conta existente não gera uma identidade retroativamente.

Quando o WebView suporta `DOCUMENT_START_SCRIPT`, a proteção é instalada antes dos scripts de cada
documento, incluindo pop-ups de login e frames. Algumas leituras de Canvas, áudio offline e capacidade
do navegador recebem variações estáveis por perfil e origem do site; detalhes da GPU são reduzidos
a informações genéricas. A geolocalização precisa é bloqueada para esses novos perfis. O ícone do
escudo no navegador informa se a proteção está ativa, indisponível ou se o perfil é anterior à mudança.

Essa é uma proteção parcial no contexto JavaScript dos documentos. Workers, OffscreenCanvas,
características de rede/TLS, fontes, tela, idioma e fuso horário podem continuar expondo dados reais.
Canvas muito grandes e alguns caminhos de leitura de áudio não são modificados; imagens exportadas
por Canvas podem ter pequenas variações de pixels. Não há VPN, proxy ou mudança de IP. Cada site
calcula sua própria fingerprint, e a diferenciação não impede todas as correlações entre perfis.
O segredo do perfil não é fornecido como identificador público nem enviado a um servidor do app.

A partir da 0.7.2, os perfis com identidade também apresentam um IPv4 privado fictício da faixa
`10.x.x.x` nas leituras WebRTC. O valor é derivado da identidade e da origem do site: permanece
estável ao reabrir e varia entre perfis e sites. São tratadas leituras de candidatos ICE (incluindo
nomes mDNS), SDP e estatísticas locais. Não são gerados candidatos ou eventos de rede falsos.
Essa alteração de metadados JavaScript não muda interfaces, rotas ou o IP público da conexão;
endereços ainda podem ser descobertos por caminhos não cobertos. A sinalização de endereços
fictícios pode impedir chamadas e conexões de dados WebRTC. Perfis anteriores sem identidade não
recebem essa alteração.

Os perfis com identidade usam um User-Agent Android gerado, sem a build real e com a versão do
Chromium limitada ao número principal real. O marcador `wv` continua presente. Na versão 0.7.3,
todos usavam a identificação genérica `Android 10; K`; a seleção por perfil é descrita a seguir.
A configuração é aplicada no WebView antes da navegação e dos pop-ups, tanto ao cabeçalho HTTP
quanto aos metadados de Client Hints quando a API correspondente é suportada. As leituras de
`navigator.userAgent`, `appVersion`, `platform` e `userAgentData` nos documentos são alinhadas:
o modelo e a versão Android seguem a combinação escolhida, enquanto arquitetura e bitness ficam
vazios nos Client Hints e a plataforma JavaScript é `Linux` sem arquitetura. Versões de WebView
sem a API de metadados usam a redução nativa do User-Agent
e a proteção JavaScript disponível; nem toda interface ou contexto do motor pode ser controlado.
A identificação de WebView é mantida. Perfis anteriores sem identidade não recebem a redução.

A partir da 0.7.4, os perfis protegidos de celular selecionam uma combinação fixa do conjunto
versionado Android 10/Pixel 4, Android 11/Pixel 4a, Android 12/Pixel 6, Android 13/Pixel 7 ou Android
14/Pixel 8. A escolha é derivada da identidade persistente do perfil, sem variar a cada abertura
ou site. User-Agent HTTP, JavaScript e Client Hints usam o mesmo modelo e a mesma versão Android
apresentados. A build real continua omitida e o Chromium mantém sua versão principal real.
Esse conjunto é finito: perfis distintos podem compartilhar a identificação de navegador, enquanto
os demais sinais continuam derivados de suas identidades próprias. Tablets mantêm a identificação
genérica para preservar o formato do dispositivo. Isso altera metadados, sem emular os aparelhos,
sem mudar o Android real e sem ocultar fontes, tela, fuso horário ou a conexão. Bateria e informações
da conexão permanecem com o comportamento anterior. Perfis sem identidade não são modificados.

- Cookies e armazenamento web permanecem no perfil privado do Android WebView.
- O aplicativo não armazena senhas em arquivos próprios.
- URLs temporárias de retorno não são exportadas nem sincronizadas.
- Excluir uma conta remove permanentemente o perfil WebView correspondente.
- Desinstalar o aplicativo ou limpar seus dados remove as sessões locais.

## Requisitos

- Android 7.0 ou superior.
- Android System WebView atualizado e com suporte a `MULTI_PROFILE`.
- Para compilar: JDK 17 e Android SDK 36.

## Instalação

Baixe o APK assinado na página de
[releases](https://github.com/RedSTwix/Nexora-Sessions-Hub/releases). Para atualizar sem perder as
sessões, instale a nova versão sobre a anterior e nunca limpe os dados do aplicativo.

### Atualizações no aplicativo

Ao abrir a tela principal, o Nexora consulta a última release estável do GitHub no máximo uma vez
a cada seis horas. Uma nova versão aparece em um aviso na tela inicial. Também é possível consultar
manualmente em **Configurações → Verificar atualizações**.

**Baixar e instalar** inicia o download com percentual e tamanho transferido. O download continua
fora do aplicativo; ao retornar, o progresso ou o arquivo concluído é recuperado. É possível cancelar
e tentar novamente. Antes de instalar, o app confere SHA-256, tamanho, pacote, versão e assinatura
do APK. O Android pode pedir permissão para instalar por essa fonte e sempre exige a confirmação
final do usuário. Se a instalação for cancelada, o botão **Instalar atualização** permite retomá-la.

A consulta acessa somente a API pública do GitHub, e o download usa o APK da release oficial.
Contas, cookies, URLs de login e sessões não são enviados. Não há verificação com o app fechado;
a continuação de um download já solicitado é gerenciada pelo Android.

As atualizações exigem a mesma chave de assinatura e um `versionCode` maior. Builds de desenvolvimento
usam o pacote separado `com.rafael.groksessions.debug`, para testes sem substituir a instalação oficial.
Esses builds não são atualizados pelas releases oficiais.

## Compilar o projeto

No Linux ou macOS:

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```

No Windows:

```powershell
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug
```

O APK de desenvolvimento será criado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Os testes da proteção JavaScript rodam em um navegador Chromium real, sem acessar sites externos:

```powershell
npm ci --prefix tests/browser
npm test --prefix tests/browser
```

No Windows, o teste utiliza o Edge instalado. Em outros sistemas, execute
`npx playwright install chromium` dentro de `tests/browser`. A variável `NEXORA_TEST_BROWSER` permite
informar o caminho de outro executável Chromium. Esses testes também são executados pelo CI.

## CI e releases

O workflow `ci` valida lint, testes e build em pushes para `main`, pull requests e execuções
manuais. O APK gerado fica disponível temporariamente como artefato de validação.

Após um push na branch `main`, o workflow `release` aguarda o `ci` terminar com sucesso e:

1. lê `versionName` e `versionCode` do Gradle;
2. exige uma chave de assinatura configurada nos GitHub Secrets;
3. compila e valida o APK assinado;
4. gera `SHA256SUMS.txt`;
5. cria a tag `vX.Y.Z` quando ela ainda não existe;
6. publica automaticamente a release e o atestado de procedência.

Novos commits com o mesmo `versionName` passam pelo CI normalmente, mas não recriam uma release
existente. O disparo manual permanece disponível para recuperação operacional.

As instruções completas estão em [docs/RELEASING.md](docs/RELEASING.md).

## Estrutura do repositório

```text
Nexora-Sessions-Hub/
├── .github/workflows/       # Validação e publicação automatizadas
├── app/src/main/java/       # Aplicação, perfis isolados e atualizações
├── app/src/main/res/        # Tema, ícones e configuração Android
├── docs/RELEASING.md        # Assinatura e processo de release
├── CONTRIBUTING.md          # Padrão de commits e contribuições
├── LICENSE                  # Licença MIT
└── README.md                # Documentação do projeto
```

## Limitações

Alguns provedores recusam autenticação dentro de WebViews. A redução do User-Agent mantém a
identificação de Android WebView; as restrições de autenticação desses provedores continuam se aplicando.

## Licença

Este projeto é distribuído sob a [licença MIT](LICENSE).
