# Adreno 730 (Snapdragon 8 / 8+ Gen 1): opções de teste

Nada aqui foi medido numa Adreno 730: são caminhos que o Automático só liga nas Adreno 8xx, liberados por variável de
ambiente para A/B. Defina em **Settings > Prelaunch > Driver variables** (`NOME=valor`, separados por espaço).

| Variável | O que faz | Observação |
| --- | --- | --- |
| `LARECOMP_A730=inplace` | Leitura direta da imagem desenhada no Automático | +30% no 830 (31 -> 40 fps). Na 750 listrou o terreno (#129): confira o terreno |
| `LARECOMP_A730=lrz` | LRZ do Turnip ligado no Automático | Reinicie o app (o Turnip lê `TU_DEBUG` uma vez) |
| `LARECOMP_A730=inplace,lrz` ou `all` | Os dois | Teste um de cada vez primeiro |
| `LARECOMP_RENDER_BIG=2` | Thread de render nos núcleos X2 + A710 (clock >= 80% do máximo) | `1` prende só no núcleo mais rápido (no 8 Gen 1, um X2 só) |
| `LARECOMP_GAMELIB_MCPU=cortex-a710` | Código do jogo compilado para essa CPU | Muda o selo do build: recompila o código (10-15 min). Ganho esperado pequeno |
| `LARECOMP_UCLAMP_MIN=512` | Piso de clock para as threads do jogo | Já existia (#41) |
| `LARECOMP_GAMEBUILD_HOT=0` | Todas as funções a -O1, sem `optnone` nas frias | Já existia; build mais demorado |

Só vale para o Turnip feito para A7xx (os builds "A8xx" derrubaram um 8+ Gen 1: #76). O Automático da tela inicial
mostra "(ligado nesta GPU)" quando uma dessas opções está valendo.

Para medir: `LARECOMP_AB=<quadros>:<chave>` alterna uma chave dentro da mesma rodada, no mesmo calor.

## Rodada automática (PC com adb)

`android/run_730_ab.sh [segundos]` roda cada opção num ciclo, com uma rodada base entre elas (o calor desloca mais que a
maioria das opções: compare cada uma com a base ao lado). A última rodada, `diag`, liga `LARECOMP_FRAME_TIMELINE` e
`LARECOMP_LOG_GPU_IDLE` e deixa em `android/out/boot-diag.log` onde o quadro gasta tempo (CPU do jogo, thread de render
ou GPU), informação para decidir se vale mexer na GPU ou na CPU. Só vale para a 1.9.0 se você usar o benchmark embutido
(`LARECOMP_BENCHMARK`, `tools/benchmark-scenes.txt`) em vez do save scriptado.
