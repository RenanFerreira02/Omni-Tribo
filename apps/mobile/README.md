# Omni-Tribo — app mobile

Expo SDK 57 · React Native 0.86 · TypeScript strict · Expo Router · TanStack Query · Zustand

```bash
cd apps/mobile
npm install
npm start          # abre o Metro; leia o QR com o Expo Go
```

Antes de qualquer coisa, o backend precisa estar de pé:

```bash
make up                                                       # PostgreSQL + PostGIS
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Usuários do seed (senha `Senha@123`): `alice@omnitribo.dev`, `bob@omnitribo.dev`,
`carol@omnitribo.dev`, `diana@omnitribo.dev`, `erik@omnitribo.dev`, `admin@omnitribo.dev` (ADMIN).

---

## O endereço da API — leia isto antes de abrir issue

**`localhost`, dentro do celular, é o PRÓPRIO celular.** Este é o erro que mais trava quem está
começando: o app abre, a tela de login aparece, e o `Entrar` fica girando até dar "não foi possível
falar com o servidor" — porque o aparelho procurou um backend em si mesmo.

| Onde o app roda | Endereço do backend |
|---|---|
| **Celular físico** (Expo Go, mesma rede Wi-Fi) | `http://<IP-da-sua-máquina>:8080` — ex.: `http://192.168.15.6:8080` |
| **Emulador Android** | `http://10.0.2.2:8080` (alias do host visto de dentro do emulador) |
| **Web / navegador na própria máquina** | `http://localhost:8080` |

**Na maioria dos casos você não precisa configurar nada.** `src/api/baseUrl.ts` deriva o endereço do
host do Metro (`Constants.expoConfig.hostUri`): o bundler já está servindo o app a partir da mesma
máquina que roda o backend, então basta trocar a porta 8081 pela 8080. Isso acerta o celular físico e
o emulador sozinho.

Para forçar um endereço, use a variável — nunca edite a URL numa chamada:

```bash
EXPO_PUBLIC_API_URL=http://192.168.15.6:8080 npm start
```

### Descobrindo o IP da máquina

```bash
ip -4 -o addr show scope global | awk '{print $2, $4}'
# enp2s0 192.168.15.6/24   ← use este, sem o /24
```

Ignore `172.17.0.1` (docker0) e `127.0.0.1`.

### O Spring precisa escutar na rede, e já escuta

Nenhum `application*.yml` define `server.address`, e o padrão do Spring Boot é vincular a
`0.0.0.0` — ou seja, aceita conexões de fora da máquina sem nenhuma mudança. Confira do próprio
celular, pelo navegador, ou de outra máquina:

```bash
curl -s http://192.168.15.6:8080/api/v1/ping
```

### Firewall

Nesta máquina (Fedora) a zona `FedoraWorkstation` já libera `1025-65535/tcp`, então a 8080 está
aberta. Em zona mais restrita:

```bash
sudo firewall-cmd --add-port=8080/tcp        # só nesta sessão
sudo firewall-cmd --add-port=8080/tcp --permanent && sudo firewall-cmd --reload
```

### Se for rodar no navegador (Expo Web)

React Native nativo não faz preflight CORS, então o celular e o emulador não esbarram nele. O
navegador esbarra: o backend restringe origens em `app.cors.origens-permitidas`. Acrescente a sua:

```bash
CORS_ORIGENS=http://localhost:8081,http://localhost:19006 ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

### `npm run android` (emulador)

Exige o Android SDK no ambiente e um AVD criado — nenhum existe por padrão nesta máquina:

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"
avdmanager list device        # escolha um perfil e crie o AVD
```

No celular físico nada disso é necessário: `npm start` + Expo Go basta, e tanto `expo-location`
quanto `expo-secure-store` funcionam nele.

---

## Comandos

```bash
npm start          # Metro
npm run android    # emulador (ver acima)
npm run typecheck  # tsc --noEmit
npm run lint       # eslint
npm test           # jest
npm run format     # prettier --write
```

## Estrutura

```
app/                 rotas do Expo Router — tela é composição, sem lógica de negócio
  (auth)/            login e registrar
  (tabs)/            área protegida: missões, carteira, perfil
  missao/[id].tsx    detalhe, ações do ciclo e check-in
src/
  api/               ÚNICO lugar que fala HTTP: cliente axios, ErroApi, endpoints
  components/        design system, sem chamada de API
  features/<dom>/    hooks de TanStack Query e lógica de domínio
  schemas/           Zod: validação de formulário e conferência de contrato
  stores/            Zustand: sessão e estado de UI
  theme/             tokens.ts é o ÚNICO arquivo com cor literal (regra travada no ESLint)
  testes/            MSW, fixtures e helper de render
```

## Build de APK com EAS

> ⚠️ **O `eas.json` está configurado, mas nenhum build foi executado neste ciclo.** Rodar um build
> exige conta Expo, autenticação e rede — nada disso é pré-requisito do projeto, e a demonstração
> acontece pelo Expo Go. O que está aqui é o procedimento pronto, não um artefato produzido.

Para a demonstração, **você não precisa de APK**: `npm start` e o QR code no Expo Go bastam, porque
todos os módulos nativos usados já vêm no Expo Go do SDK 57. O APK só é necessário para distribuir
o app a quem não vai instalar o Expo Go.

### Perfis definidos em `eas.json`

| Perfil | Saída | Para quê |
|---|---|---|
| `development` | APK com *dev client* | depurar módulo nativo que não existe no Expo Go |
| `preview` | **APK**, distribuição interna | é este que se manda por link ou WhatsApp para alguém instalar |
| `production` | AAB (*app bundle*) | formato exigido pela Play Store; **não instala direto no aparelho** |

### Procedimento

```bash
npm install -g eas-cli          # ou: npx eas-cli@latest
eas login                       # exige conta Expo (gratuita)

cd apps/mobile
eas init                        # cria o projeto na conta e grava o projectId em app.config.ts
eas build --platform android --profile preview
```

O build roda **na infraestrutura da Expo**, não na sua máquina — no fim, a CLI imprime uma URL de
download do `.apk`. Fila e duração variam com o plano da conta.

### Três coisas que vão morder

1. **`appVersionSource` está como `local`**: a versão vem do `version: '1.0.0'` de `app.config.ts`.
   Suba-a a cada build que for distribuído, ou os artefatos ficam indistinguíveis.
2. **`production` gera AAB e não instala no aparelho.** Para instalar direto, é `preview`.
3. **A URL da API precisa ser alcançável pelo aparelho.** O padrão deriva do host do Metro, que não
   existe num APK autônomo — passe `EXPO_PUBLIC_API_URL` no build, apontando para um endereço que o
   aparelho enxergue. `localhost` aponta para o próprio telefone e vai falhar em silêncio:

   ```bash
   EXPO_PUBLIC_API_URL=http://192.168.15.6:8080 eas build -p android --profile preview
   ```

   Um backend rodando na sua máquina só é alcançável enquanto o aparelho estiver na mesma rede.

## Decisões que valem conhecer antes de mexer

- **Erro é discriminado pelo `type` do ProblemDetail, NUNCA pelo `detail`.** O catálogo vive em
  `compartilhado/api/TipoProblema` no backend; o espelho é `src/api/erros.ts`. Ver ADR 0010.
- **Credencial em `expo-secure-store`, nunca AsyncStorage.** O access token fica só em memória.
- **Recompensa é XP + TOKEN. Nunca reais.** `valorBrl` chega 0 e nenhum componente o exibe
  (ADR 0009). Há teste garantindo que `R$` não aparece na árvore renderizada.
- **Distância vem do PostGIS.** O app formata; não recalcula.
- **Um `Idempotency-Key` por operação lógica**, estável entre retries da mesma ação.
