# scripts

Ferramentas que cruzam os projetos do monorepo. Não criam dependência de build
entre eles: só orquestram comandos que cada projeto já sabe rodar.

## `android-release.sh`

Builda os APKs Android de release dos dois apps — Notas (`notes/Android`) e
Calendário (`agenda/android`) — **sempre assinados com a mesma chave**, e
verifica a assinatura depois de cada build.

### Por que isso existe

O Android só instala uma atualização por cima quando o APK novo tem o mesmo
`applicationId` **e** a mesma assinatura. Se a keystore for recriada — outra
máquina, `~` formatado, build em CI sem o segredo — o sistema recusa com *"App
not installed"* e a única saída é desinstalar. Desinstalar apaga os dados locais
do app: notas que ainda não subiram, eventos, configurações, token de sync.

Recriar a keystore "só quando dá erro" é o pior dos mundos, porque o erro aparece
no celular, não na máquina de build. Por isso a chave é tratada como
infraestrutura: um cofre versionado à parte do código, com fingerprint conferido
em toda build.

### Onde fica o cofre

Padrão: `~/.config/atena/android-keys` (sobrescreva com `ATENA_ANDROID_KEYS`).

```
~/.config/atena/android-keys/
├── credentials.env        # senhas e alias (chmod 600)
├── notes.jks              # keystore do Notas
├── notes.fingerprint      # SHA-256 esperado do certificado
├── calendar.jks           # keystore do Calendário
└── calendar.fingerprint
```

Nada disso é versionado: `.gitignore` já cobre `*.jks`, `*.keystore` e
`local.properties`. O script escreve os arquivos que o Gradle espera
(`notes/Android/local.properties` e `agenda/android/keystore.properties`)
apontando para o cofre, então um `./gradlew assembleRelease` avulso também
assina certo.

| App | applicationId (release) | Alias | Certificado |
| --- | --- | --- | --- |
| Notas | `com.openappslabs.jotter` | `jotter` | `C731A338…29AF12` |
| Calendário | `org.fossify.calendar` | `atena-calendar` | `2AFB2F9E…E9C319` |

> Cuidado com os builds `debug`: eles usam sufixo no pacote
> (`…jotter.debug`, `org.fossify.calendar.debug`), então **não** atualizam o app
> de release — instalam ao lado. Para subir atualização, use o APK de release.

### Uso

```bash
./scripts/android-release.sh                 # notas + calendário
./scripts/android-release.sh --check         # só valida cofre, SDK e fingerprints
./scripts/android-release.sh --only notes    # um app só
./scripts/android-release.sh --bump          # +1 no versionCode e builda
```

Os APKs são copiados para `~/Downloads/atena-apks` (mude com `ATENA_APK_OUT`),
com `SHA256SUMS.txt` ao lado.

Se o APK sair assinado por uma chave diferente da do cofre, o script **aborta**
em vez de entregar um arquivo que exigiria desinstalação:

```
erro: notas: assinatura DIFERENTE do cofre!
  esperado: c731a338…
  no APK:   9f0b2d11…
```

### Backup — a parte que importa

Perder o cofre é perder a capacidade de atualizar os apps. Faça backup e guarde a
senha em outro lugar:

```bash
./scripts/android-release.sh --backup               # ~/atena-android-keys-<data>.tar.gz.enc
./scripts/android-release.sh --restore <arquivo>    # em outra máquina
```

O backup é um `tar.gz` cifrado com AES-256 (`openssl enc -pbkdf2`), pedindo senha
na hora. Sem a senha ele não abre — e sem o backup não há como recuperar a
assinatura.

### `versionCode` e atualização

Assinatura igual é condição necessária, mas o Android também compara versão:
reinstalar a mesma versão funciona (`adb install -r`), porém rebaixar exige flag.
Quando a mudança for uma release de verdade, rode com `--bump`; o script
incrementa o contador **antes** do build e avisa o valor novo.

O `--bump` altera código-fonte de propósito e de forma explícita:
`notes/Android/app/build.gradle.kts` (`versionCode`) e
`agenda/android/gradle.properties` (`VERSION_CODE`). Commite essa mudança
separada do resto, seguindo a regra de um assunto por commit.
