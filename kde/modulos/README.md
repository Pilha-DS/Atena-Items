# módulos do KDE

Código nosso que **estende** o KDE, para o que a configuração não resolve.
Aqui não entra config: config mora em `../configs/`.

Cada módulo tem build e README próprios, e só existe quando a escada do
[CONFIG-MAP.md](../CONFIG-MAP.md) já passou por config e override — e o custo de
sincronizar com o upstream do KDE está escrito no README dele.

| Módulo | O que é | Status |
| --- | --- | --- |
| `settings/` | uma janela para as telas de configuração do Plasma, com o agrupamento definido em `layout.conf` (6 grupos, 35 abas, 73 KCMs) | funcionando; sem instalador e sem busca |
| `explorer/` | explorador de arquivos próprio — só se o Dolphin (config + `dolphinui.rc`) travar em algo concreto | em avaliação |

`settings/` hospeda os KCMs nativos; ele não reimplementa nenhum ajuste. O
agrupamento (grupos, abas e as sub-abas de quem tem mais de um KCM) vem de
`settings/layout.conf`, versionado, e `./build/atena-settings --check` falha se
algum KCM da máquina ficar fora do layout. Detalhes:
[settings/README.md](settings/README.md).