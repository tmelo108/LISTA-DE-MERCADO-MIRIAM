# AutoVideo AI (Android nativo)

Projeto Android em Kotlin com Jetpack Compose/Material 3. A exportação é local no aparelho usando Media3 Transformer; não há serviço de renderização remoto.

## Abrir e executar

1. Abra a pasta raiz deste repositório em Android Studio Ladybug Feature Drop (2024.2.2) ou mais recente.
2. Use JDK 17, instale/seleciona Gradle 8.10.2 e sincronize. O projeto usa Android Gradle Plugin 8.8.2, compile/target SDK 35 e min SDK 26.
3. Execute o módulo `app` em um aparelho/emulador Android.
4. Em **Ajustes → APIs e integrações**, informe as chaves que deseja usar. Sem chave Gemini não há transcrição/análise; Pexels/Pixabay são opcionais se a biblioteca local tiver mídias adequadas.

> Este repositório não inclui o Gradle Wrapper. O ambiente de trabalho também não possui Java, Android SDK ou Gradle instalados. Portanto, não foi possível executar `assembleDebug` nem instalar o APK aqui. Instale Gradle 8.10.2 ou configure o Android Studio para usar uma instalação local; compile e valide em um ambiente Android antes da distribuição.

## Fluxo funcional implementado

- Seleção segura de narração via Storage Access Framework (MP3, WAV, M4A, AAC), leitura de duração/tamanho e cópia incremental para a pasta privada do projeto.
- Envio do áudio ao Gemini Files API em fluxo (sem carregar o áudio inteiro na RAM), transcrição e retorno JSON de microcenas/timestamps; geração de `script.txt`, `script.srt` e `scenes.json`.
- Busca sequencial em Pexels e Pixabay, pontuação por texto/orientação/resolução/duração, consulta de mídia local e cache SHA-256. O YouTube Data API v3 é usado somente para pesquisa de referências; nenhum vídeo do YouTube é baixado.
- Quantidade exata de slots de vídeo/imagem configurada pelo usuário e distribuição ponderada ao longo da narração. Nos modos automático/proporcional/uma mídia por microcena, a preferência de vídeo/imagem sugerida pela cena orienta a alocação sem alterar as quotas. Quando a quantidade não é encontrada, o projeto informa o faltante e oferece nova tentativa, redução ou repetição.
- Room para projetos/cenas/mídias/presets e WorkManager com fila, foreground notification, retomada por etapa e retry exponencial.
- Edição manual de takes, nova pesquisa, escolha de arquivo via SAF, biblioteca local e links de atribuição/licença.
- Prévia de 20 segundos e renderização MP4 local com Media3 Transformer: H.264 (H.265 quando o encoder é detectado), corte/concatenação, enquadramento 16:9/9:16/1:1, FPS configurável, volume da narração e movimento Ken Burns/pan/zoom. Há estimativa de espaço para downloads e uma checagem antes de iniciar a exportação; miniaturas locais são criadas para vídeos importados/baixados.
- Preview com ExoPlayer, compartilhamento via FileProvider, cópia para Filmes/AutoVideo AI e arquivos de projeto privados organizados em `AutoVideoAI/Projetos/<id-nome>/`.
- Chaves de APIs cifradas com AES-GCM usando chave criada no Android Keystore.

## Transparência: itens ainda não implementados

Estas funções não são simuladas nem aparentam funcionar na interface:

- **Transições entre takes:** a montagem usa cortes secos. Crossfade/dissolve/slide entre sequências não está ativo neste build do Media3.
- **Música de fundo e fades:** a mixagem de trilha local e automação de fade ainda não está conectada ao exportador. O controle funcional de áudio é o volume da narração.
- **Legendas queimadas:** o SRT sincronizado é gerado como arquivo separado; desenhar texto sobre os frames durante a exportação ainda não foi conectado.
- **Descrição automática de itens da biblioteca:** as tags locais iniciais vêm do nome do arquivo; não há indexador visual por IA para vídeos/imagens.
- **Retomada de renderização parcial:** o WorkManager preserva projeto e mídias e retoma as etapas de análise/busca; um MP4 incompleto é descartado e uma exportação cancelada precisa recomeçar.
- O campo de duração de take orienta a segmentação do Gemini e a pontuação de candidatos. Para sincronizar a trilha inteira com uma quantidade reduzida de mídias, os slots finais são distribuídos ao longo da duração total.

## Estrutura

```text
app/src/main/java/ai/autovideo/app/
  data/       Room, arquivos, Retrofit e repositórios
  domain/     Planejador de distribuição
  render/     Media3 Transformer, efeitos de movimento e áudio
  security/   Cifra de chaves com Android Keystore
  ui/         Jetpack Compose, telas e ViewModel
  work/       Workers e fila WorkManager
```

As chaves nunca são incluídas em `BuildConfig` ou no código. Configure-as apenas na tela do app. Respeite a licença/atribuição de cada ativo e os limites e termos atuais das APIs gratuitas.
