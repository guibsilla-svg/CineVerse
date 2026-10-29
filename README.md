# CineVerse — app Android

**Seu universo em cada tela.** Aplicativo pessoal para organizar filmes e séries com identidade visual preta e roxo neon, ícone adaptável CineVerse, listas, progresso básico de séries, estatísticas, roleta, calendário local e backup JSON.

## Tecnologias
- Kotlin + Jetpack Compose
- Room / SQLite local
- TMDB API para pesquisar títulos e metadados
- Coil para carregar imagens
- GitHub Actions para gerar APK de teste

## Gerar o APK no GitHub Actions
1. Crie um repositório privado chamado `CineVerse` na sua conta do GitHub.
2. Extraia este ZIP e envie o conteúdo da pasta `CineVerse` para a raiz do repositório (incluindo a pasta `.github`).
3. Abra **Actions** e habilite workflows se o GitHub solicitar.
4. Abra **Build CineVerse APK** e toque em **Run workflow**, ou faça um commit na branch `main`.
5. Aguarde o job terminar com status verde. A execução deve publicar o artefato `cineverse-debug-apk`.
6. Baixe e extraia o ZIP do artefato; o arquivo para instalar é `app-debug.apk`.
7. No Android, permita a instalação pelo navegador/gerenciador de arquivos e abra o APK.

## TMDB
Em Ajustes, informe sua chave de API do TMDB. Não publique a chave em repositório público. Para uso não comercial, consulte as regras e atribuição exigida pelo TMDB; antes de qualquer monetização/distribuição comercial, confirme o licenciamento aplicável.

Aviso de atribuição a ser exibido no app: `This product uses the TMDB API but is not endorsed or certified by TMDB.`

## Importante sobre esta entrega
Este é um MVP de desenvolvimento, não uma versão validada para publicação. O projeto foi atualizado com a marca CineVerse e ícones Android adaptáveis/legados. O APK precisa ser compilado no GitHub Actions para validar o build; este ambiente não possui Android SDK/Gradle instalado, portanto não é possível afirmar que um APK instalável foi compilado aqui. O aplicativo usa applicationId `br.com.cineverse`, compileSdk/targetSdk 35 e versão 1.0.0. Recursos de detalhes completos de temporadas/episódios e calendário global de lançamentos ainda são limitados.
