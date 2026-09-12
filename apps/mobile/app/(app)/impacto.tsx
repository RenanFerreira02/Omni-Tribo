import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mensagemDe } from '@/api/erros';
import type { ImpactoResponse } from '@/api/tipos';
import { Aviso } from '@/components/Aviso';
import { BarraProgresso } from '@/components/BarraProgresso';
import { Card } from '@/components/Card';
import { TituloTela } from '@/components/TituloTela';
import { Esqueleto } from '@/components/Esqueleto';
import { useImpacto } from '@/features/impacto/hooks';
import { cores, espaco, textoAcessivel, tipografia, traco } from '@/theme';

/**
 * O painel que responde "quanto a tese economizou" — a única tela do app que fala de VALOR.
 *
 * **Nenhuma biblioteca de gráfico.** As barras são `BarraProgresso`, que já existe, é `View` pura e
 * já tem `accessibilityRole="progressbar"` com valor. Um gráfico de verdade traria dependência
 * nativa, um segundo sistema de cor e nada que quatro números grandes não digam melhor.
 *
 * **A premissa é a primeira coisa depois do custo evitado, não uma nota de rodapé.** O número que
 * uma banca ataca é o custo por re-entrega, porque ninguém o mediu — e ele está aqui declarado como
 * premissa, com o valor vigente e a mesma conta a ±50%. A tela não tenta esconder a fragilidade;
 * ela a apresenta como faixa, que é o que uma análise de sensibilidade é.
 *
 * **Nada é calculado aqui.** Toda taxa, soma e mediana vem do servidor. Recalcular no cliente
 * criaria uma segunda fórmula para os mesmos indicadores — o defeito que o `previa-recompensa`
 * existe para evitar do lado das missões.
 */
export default function TelaImpacto() {
  const impacto = useImpacto();

  return (
    <SafeAreaView style={estilos.tela} edges={['bottom']}>
      <ScrollView contentContainerStyle={estilos.conteudo}>
        <TituloTela>Impacto</TituloTela>
        <Text style={estilos.legenda}>
          Apurado no servidor a cada consulta, sobre os dados de produção. Não há cache: dois
          pedidos seguidos podem diferir.
        </Text>

        {impacto.isPending ? <Carregando /> : null}

        {impacto.isError ? (
          <Aviso
            tom={impacto.error.tipo === 'acessoNegado' ? 'atencao' : 'erro'}
            titulo={
              impacto.error.tipo === 'acessoNegado' ? 'Painel restrito' : 'Não deu para apurar'
            }
            mensagem={
              impacto.error.tipo === 'acessoNegado'
                ? 'Só contas administradoras enxergam os indicadores de impacto.'
                : mensagemDe(impacto.error)
            }
            testID="erro-impacto"
          />
        ) : null}

        {impacto.data ? <Painel dados={impacto.data} /> : null}
      </ScrollView>
    </SafeAreaView>
  );
}

function Painel({ dados }: { dados: ImpactoResponse }) {
  const { entregasFalidas: ef, missoesDeRetirada: mr, custoEvitado: ce, tokens } = dados;

  return (
    <View style={estilos.secoes}>
      {/* ─── Custo evitado, e a premissa logo abaixo ────────────────────────────────────── */}
      <TituloTela nivel="secao">Custo evitado</TituloTela>

      <Card>
        <Text
          style={estilos.numeroGrande}
          accessibilityLabel={`Custo evitado estimado: ${reais(ce.baseBrl)}`}
          testID="custo-base"
        >
          {reais(ce.baseBrl)}
        </Text>
        <Text style={estilos.legenda}>
          {ce.reentregasEvitadas} re-entrega{ce.reentregasEvitadas === 1 ? '' : 's'} evitada
          {ce.reentregasEvitadas === 1 ? '' : 's'} × {reais(ce.premissaCustoReentregaBrl)}
        </Text>
        <Text style={estilos.legenda}>
          &ldquo;Re-entrega evitada&rdquo; é a missão de retirada concluída, com outro nome — e
          assume que a encomenda teria sido re-entregue. É uma interpretação do mesmo número, não
          uma segunda medição.
        </Text>

        <View style={estilos.linhaDivisoria} />

        <FaixaSensibilidade custoEvitado={ce} />
      </Card>

      <Aviso
        tom="atencao"
        titulo="Premissa, não medição"
        mensagem={
          `${reais(ce.premissaCustoReentregaBrl)} por re-entrega é uma PREMISSA de configuração: ` +
          `este projeto não mediu esse custo e não tem operação real para medi-lo. ` +
          `É por isso que a faixa acima existe — o que se pode defender é a ordem de grandeza que ` +
          `sobrevive a ela, não o número do meio.`
        }
        testID="aviso-premissa"
      />

      {/* ─── Funil ──────────────────────────────────────────────────────────────────────── */}
      <TituloTela nivel="secao">Entregas falidas</TituloTela>

      <Card>
        <Numero rotulo="Recebidas" valor={ef.recebidas} testID="ef-recebidas" />

        <Etapa
          rotulo="Convertidas em missão"
          valor={ef.convertidas}
          total={ef.recebidas}
          taxa={ef.taxaConversao}
          testID="ef-convertidas"
        />
        {/*
          `criadas` aparece como degrau PRÓPRIO do funil, e não como denominador escondido, porque
          ele pode ser MENOR que `convertidas` legitimamente: entrega falida do seed histórico
          aponta para missão criada por humano, já que o seed é anterior ao usuário-sistema. Sem
          esta linha, a taxa de conclusão sairia calculada sobre um número que não está na tela — e
          quem lê concluiria que a conversão gravou pela metade.
        */}
        <Numero
          rotulo="Missões de retirada criadas"
          valor={mr.criadas}
          legenda={
            mr.criadas < ef.convertidas
              ? `Menor que as ${ef.convertidas} convertidas porque parte delas é de dados históricos, ligada a missões criadas por pessoas — não pelo sistema.`
              : undefined
          }
          testID="mr-criadas"
        />
        <Etapa
          rotulo="Concluídas"
          valor={mr.concluidas}
          total={mr.criadas}
          taxa={mr.taxaConclusao}
          testID="mr-concluidas"
        />

        <View style={estilos.linhaDivisoria} />

        <Numero
          rotulo="Na custódia, sem missão"
          valor={ef.pendentes}
          legenda="Encomendas recebidas que não viraram missão nem foram recusadas. É o que explica uma taxa de conversão baixa."
          testID="ef-pendentes"
        />
        <Numero
          rotulo="Recusadas: ponto lotado"
          valor={ef.recusadasPontoLotado}
          legenda="Falta de capacidade no bairro."
          testID="ef-lotado"
        />
        <Numero
          rotulo="Recusadas: sem patrocínio"
          valor={ef.recusadasSemPatrocinio}
          legenda="Transportadora sem carteira ativa ou sem saldo para o pote."
          testID="ef-sem-patrocinio"
        />
      </Card>

      {/* ─── Tempo de resposta ──────────────────────────────────────────────────────────── */}
      <TituloTela nivel="secao">Tempo de resposta do bairro</TituloTela>

      <Card>
        <Text
          style={estilos.numeroGrande}
          accessibilityLabel={
            mr.medianaAteCheckinSegundos === null
              ? 'Tempo mediano até o check-in: sem dados suficientes'
              : `Tempo mediano até o check-in: ${duracao(mr.medianaAteCheckinSegundos)}`
          }
          testID="mediana"
        >
          {mr.medianaAteCheckinSegundos === null ? '—' : duracao(mr.medianaAteCheckinSegundos)}
        </Text>
        <Text style={estilos.legenda}>
          Mediana entre o aviso da transportadora e o primeiro check-in válido do executor.
        </Text>
        <Text style={estilos.legenda} testID="amostra">
          {mr.amostraMediana === 0
            ? 'Nenhuma missão com check-in ainda — não há o que medir.'
            : `Amostra: ${mr.amostraMediana} ${mr.amostraMediana === 1 ? 'missão' : 'missões'}.` +
              (mr.amostraMediana < 5 ? ' Pequena demais para concluir tendência.' : '')}
        </Text>
      </Card>

      {/* ─── Token ──────────────────────────────────────────────────────────────────────── */}
      <TituloTela nivel="secao">Token em circulação</TituloTela>

      <Card>
        <Numero
          rotulo="Aportados por patrocinadores"
          valor={tokens.aportados}
          testID="tk-aportados"
        />
        <Numero rotulo="Em carteiras" valor={tokens.emCarteiras} testID="tk-carteiras" />
        <Numero rotulo="Em potes de missão" valor={tokens.emPotes} testID="tk-potes" />
        <Numero
          rotulo="Em circulação"
          valor={tokens.emCirculacao}
          legenda="Carteiras + potes. Token em pote saiu de uma carteira e ainda não chegou na outra."
          testID="tk-circulacao"
        />
        <Numero
          rotulo="Resgatados (queimados)"
          valor={tokens.resgatados}
          legenda="Saíram da economia em troca de benefício. Não voltam."
          testID="tk-resgatados"
        />
      </Card>

      <Text style={estilos.legenda} testID="gerado-em">
        Apurado em {dataHora(dados.geradoEm)}.
      </Text>
    </View>
  );
}

/**
 * A análise de sensibilidade em três colunas de peso IGUAL — e o peso igual é o argumento inteiro.
 *
 * O ADR 0029 §5 diz que "a conclusão defensável não é o número do meio, é a ordem de grandeza que
 * sobrevive à faixa". Enquanto os três valores viviam numa frase corrida, só o do meio tinha
 * destaque tipográfico e os outros dois liam como ressalva de rodapé — exatamente a leitura que o
 * ADR recusa. Lado a lado, a primeira coisa que se vê é a comparação, não o total.
 *
 * **Os três TOTAIS vêm do servidor** (`menos50Brl`, `baseBrl`, `mais50Brl`) e nenhum é recalculado
 * aqui. O FATOR impresso acima de cada total é outra coisa e não existe na resposta: sai de
 * `premissaCustoReentregaBrl` vezes 0,5 e 1,5, só para dizer de QUAL premissa aquela coluna saiu.
 * É rótulo do cenário — não a conta que produziu o número abaixo dele, que já veio pronta.
 */
function FaixaSensibilidade({
  custoEvitado: ce,
}: {
  custoEvitado: ImpactoResponse['custoEvitado'];
}) {
  const cenarios = [
    {
      chave: 'menos50',
      rotulo: 'Premissa ÷ 2',
      fala: 'Premissa pela metade',
      premissa: ce.premissaCustoReentregaBrl * 0.5,
      total: ce.menos50Brl,
    },
    {
      chave: 'base',
      rotulo: 'Premissa vigente',
      fala: 'Premissa vigente',
      premissa: ce.premissaCustoReentregaBrl,
      total: ce.baseBrl,
    },
    {
      chave: 'mais50',
      rotulo: 'Premissa × 1,5',
      fala: 'Premissa uma vez e meia',
      premissa: ce.premissaCustoReentregaBrl * 1.5,
      total: ce.mais50Brl,
    },
  ];

  return (
    <View style={estilos.faixa} testID="faixa-sensibilidade">
      {cenarios.map((cenario) => (
        <View
          key={cenario.chave}
          style={estilos.cenario}
          // Cada COLUNA é um nó acessível; a LINHA não. Agrupar as três num nó só entregaria seis
          // valores monetários seguidos, sem separação audível entre cenário e cenário — que é
          // justamente a comparação que esta faixa existe para permitir.
          accessible
          accessibilityLabel={`${cenario.fala}, ${reais(cenario.premissa)} por re-entrega: total ${reais(cenario.total)}`}
        >
          <Text style={estilos.cenarioRotulo}>{cenario.rotulo}</Text>
          <Text style={estilos.cenarioPremissa}>{reais(cenario.premissa)}</Text>
          <Text style={estilos.cenarioTotal} testID={`cenario-${cenario.chave}`}>
            {reais(cenario.total)}
          </Text>
        </View>
      ))}
    </View>
  );
}

/** Etapa do funil: número, barra e taxa. A barra é decorativa — quem lê o valor é o rótulo. */
function Etapa({
  rotulo,
  valor,
  total,
  taxa,
  testID,
}: {
  rotulo: string;
  valor: number;
  total: number;
  taxa: number | null;
  testID: string;
}) {
  // `null` vira travessão, NUNCA "0%": com denominador zero não há desempenho a relatar, e exibir
  // zero afirmaria fracasso onde não houve tentativa.
  const texto = taxa === null ? '—' : `${(taxa * 100).toFixed(1).replace('.', ',')}%`;
  const acessivel = taxa === null ? 'sem dados suficientes' : texto;

  return (
    <View style={estilos.item}>
      <View style={estilos.itemCabecalho}>
        <Text style={estilos.rotulo}>{rotulo}</Text>
        <Text style={estilos.valor} testID={testID}>
          {valor}
        </Text>
      </View>
      <BarraProgresso
        valor={valor}
        meta={total}
        rotuloAcessivel={`${rotulo}: ${valor} de ${total}, ${acessivel}`}
        testID={`${testID}-barra`}
      />
      <Text style={estilos.legenda}>{texto} do passo anterior</Text>
    </View>
  );
}

function Numero({
  rotulo,
  valor,
  legenda,
  testID,
}: {
  rotulo: string;
  valor: number;
  legenda?: string;
  testID: string;
}) {
  return (
    <View style={estilos.item}>
      {/* Rótulo e valor num único nó acessível: lidos separados, viram "Recebidas" … "22" com
          outro conteúdo no meio, e o leitor de tela perde o par. */}
      <View style={estilos.itemCabecalho} accessible accessibilityLabel={`${rotulo}: ${valor}`}>
        <Text style={estilos.rotulo}>{rotulo}</Text>
        <Text style={estilos.valor} testID={testID}>
          {valor}
        </Text>
      </View>
      {legenda ? <Text style={estilos.legenda}>{legenda}</Text> : null}
    </View>
  );
}

function Carregando() {
  return (
    <View style={estilos.secoes} testID="impacto-carregando">
      <Esqueleto altura={120} />
      <Esqueleto altura={200} />
      <Esqueleto altura={140} />
    </View>
  );
}

/**
 * `12345.67` → `R$ 12.345,67`.
 *
 * Só FORMATAÇÃO. O `toFixed` aqui não é aritmética de dinheiro: o valor já chegou calculado em
 * `BigDecimal` pelo servidor, e nenhuma conta é refeita no cliente — a mesma disciplina que faz a
 * recompensa vir de `previa-recompensa` em vez de ser duplicada aqui.
 */
function reais(valor: number): string {
  const [inteiro, decimal] = valor.toFixed(2).split('.');
  const comSeparador = inteiro.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return `R$ ${comSeparador},${decimal}`;
}

/** Segundos → a maior unidade que ainda é legível. "2 h 15 min" diz mais que "8100 s". */
function duracao(segundos: number): string {
  if (segundos < 60) return `${segundos} s`;
  const minutos = Math.floor(segundos / 60);
  if (minutos < 60) return `${minutos} min`;
  const horas = Math.floor(minutos / 60);
  const resto = minutos % 60;
  if (horas < 24) return resto === 0 ? `${horas} h` : `${horas} h ${resto} min`;
  const dias = Math.floor(horas / 24);
  const horasRestantes = horas % 24;
  return horasRestantes === 0 ? `${dias} d` : `${dias} d ${horasRestantes} h`;
}

function dataHora(iso: string): string {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()} às ${p(d.getHours())}:${p(d.getMinutes())}`;
}

const estilos = StyleSheet.create({
  tela: { flex: 1, backgroundColor: cores.papel },
  conteudo: { padding: espaco.lg, gap: espaco.md, paddingBottom: espaco.xxl },
  secoes: { gap: espaco.md },
  numeroGrande: { ...tipografia.display, color: cores.verdeEscuro },
  rotulo: { ...tipografia.rotulo, color: cores.tinta70, flexShrink: 1 },
  valor: { ...tipografia.subtitulo, color: cores.tinta },
  legenda: { ...tipografia.legenda, color: textoAcessivel.suave },
  item: { gap: espaco.xs },
  // Três colunas de mesma largura: `flex: 1` em cada, e nenhuma altura fixa — o rótulo pode quebrar
  // em duas linhas num aparelho estreito ou com fonte aumentada sem cortar o número de baixo.
  faixa: { flexDirection: 'row', gap: espaco.sm },
  cenario: { flex: 1, gap: espaco.xxs },
  cenarioRotulo: { ...tipografia.legenda, color: textoAcessivel.suave },
  cenarioPremissa: { ...tipografia.legenda, color: cores.tinta70 },
  cenarioTotal: { ...tipografia.subtitulo, color: cores.tinta },
  itemCabecalho: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  linhaDivisoria: { height: traco, backgroundColor: cores.linha },
});
