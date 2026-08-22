import { useRouter } from 'expo-router';
import { useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import type { FiltroBeneficios } from '@/api/beneficios';
import { mensagemDe, type ErroApi } from '@/api/erros';
import type { BeneficioResponse } from '@/api/tipos';
import { BarraProgresso } from '@/components/BarraProgresso';
import { Botao } from '@/components/Botao';
import { Card } from '@/components/Card';
import { Esqueleto } from '@/components/Esqueleto';
import { EstadoVazio } from '@/components/EstadoVazio';
import { FolhaInferior } from '@/components/FolhaInferior';
import { SaldoToken } from '@/components/SaldoToken';
import { useBeneficios, useResgatar } from '@/features/beneficios/hooks';
import { estadoDoResgate } from '@/features/beneficios/catalogo';
import { useCarteira } from '@/features/carteira/hooks';
import { usePerfil } from '@/features/perfil/hooks';
import { novaChaveIdempotencia } from '@/lib/ids';
import { cores, espaco, textoAcessivel, tipografia } from '@/theme';

/**
 * Para que serve o token — agora de verdade.
 *
 * Esta tela era VITRINE: catálogo hardcoded e um aviso dizendo que o resgate era "combinado no
 * balcão" porque nada era descontado. Aquele aviso era honesto enquanto o sumidouro não existia; com
 * a F16 (V24-V26, ADR 0027) ele virou mentira e saiu.
 *
 * **O saldo só muda quando o servidor confirma.** Não há débito otimista aqui, e a assimetria com as
 * ações de missão é deliberada — a mesma razão registrada em `useTransferirTokens`: o que mudaria na
 * tela é DINHEIRO, e um número que aparece debitado e volta atrás depois de um 422 é a pior forma de
 * mostrá-lo. Com um sumidouro isso é pior ainda, porque token queimado não volta.
 */
export default function TelaBeneficios() {
  const router = useRouter();
  const carteira = useCarteira();
  const perfil = usePerfil();

  const [selecionado, setSelecionado] = useState<BeneficioResponse | null>(null);
  const [confirmando, setConfirmando] = useState(false);

  const resgatar = useResgatar();
  const saldo = carteira.data?.saldoTokens ?? 0;

  /**
   * A chave de idempotência nasce com a INTENÇÃO, não com o toque.
   *
   * Gerada quando a folha abre e rotacionada só depois de um resgate bem-sucedido: um retry de rede
   * — o caso em que o servidor queimou mas a resposta se perdeu — repete a MESMA chave e recebe o
   * replay, em vez de queimar de novo. Mesmo padrão da transferência.
   */
  const chave = useRef(novaChaveIdempotencia());

  /**
   * O recorte do catálogo.
   *
   * Por TRIBO, e não por proximidade, e isso é decisão de privacidade herdada: `useLocalizacao` não
   * pede permissão ao montar — a auditoria mobile cobrou exatamente isso da aba de missões, que
   * gastava o prompt do sistema sem ter o que mostrar em troca. Abrir uma tela de catálogo não é
   * motivo para pedir GPS. O endpoint aceita os dois recortes; quem quiser o de raio pede a
   * localização explicitamente, em trabalho seguinte.
   */
  const filtro: FiltroBeneficios | null = useMemo(() => {
    const triboId = perfil.data?.tribo?.id;
    return triboId ? { triboId } : null;
  }, [perfil.data?.tribo?.id]);

  const catalogo = useBeneficios(filtro);
  const beneficios = catalogo.data?.conteudo ?? [];

  const resultado = resgatar.data;
  const erroResgate = resgatar.error;

  /**
   * O desfecho é ANUNCIADO, não só desenhado.
   *
   * Quem usa leitor de tela não vê a folha trocar de conteúdo: sem isto, o resgate "acontece" em
   * silêncio e a pessoa fica sem saber se o token foi queimado. O anúncio inclui o código, porque é
   * a única informação que ela precisa levar ao balcão.
   */
  useEffect(() => {
    if (resultado) {
      AccessibilityInfo.announceForAccessibility(
        `Resgate concluído. Seu código de retirada é ${soletrar(resultado.codigoRetirada)}. ` +
          `Saldo restante: ${resultado.saldoTokensRestante} tokens.`,
      );
    }
  }, [resultado]);

  useEffect(() => {
    if (erroResgate) {
      AccessibilityInfo.announceForAccessibility(
        `Não foi possível resgatar. ${mensagemDe(erroResgate)}`,
      );
    }
  }, [erroResgate]);

  function abrir(beneficio: BeneficioResponse) {
    chave.current = novaChaveIdempotencia();
    resgatar.reset();
    setConfirmando(false);
    setSelecionado(beneficio);
  }

  function fechar() {
    if (resgatar.isSuccess) {
      // Só rotaciona depois do sucesso: reaproveitar a chave prenderia o próximo resgate no replay
      // eterno deste, inclusive no de uma rejeição.
      chave.current = novaChaveIdempotencia();
    }
    resgatar.reset();
    setConfirmando(false);
    setSelecionado(null);
  }

  if (carteira.error) {
    return (
      <SafeAreaView style={estilos.raiz} testID="tela-beneficios">
        <EstadoVazio
          testID="beneficios-erro"
          titulo="Não deu para carregar seu saldo"
          descricao={mensagemDe(carteira.error)}
          acao={{ rotulo: 'Tentar de novo', onPress: () => void carteira.refetch() }}
        />
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={estilos.raiz} testID="tela-beneficios">
      <ScrollView contentContainerStyle={estilos.corpo}>
        <View style={estilos.cabecalho}>
          <Text style={estilos.titulo} accessibilityRole="header">
            Resgatar no bairro
          </Text>
          <Botao
            titulo="Voltar"
            variante="texto"
            onPress={() => router.back()}
            testID="botao-voltar-beneficios"
          />
        </View>

        <Card estilo={estilos.saldo}>
          <Text style={estilos.rotuloSaldo}>Seu saldo</Text>
          {carteira.isLoading ? (
            <Esqueleto largura={120} altura={34} />
          ) : (
            <SaldoToken tokens={saldo} tamanho="grande" testID="saldo-beneficios" />
          )}
          <Text style={estilos.explicacao}>
            Tokens resgatados saem de circulação. É assim que a moeda da tribo vira algo concreto no
            bairro.
          </Text>
        </Card>

        <ConteudoCatalogo
          carregando={catalogo.isLoading || perfil.isLoading}
          erro={catalogo.error ? mensagemDe(catalogo.error) : null}
          aoTentarDeNovo={() => void catalogo.refetch()}
          beneficios={beneficios}
          saldo={saldo}
          aoAbrir={abrir}
        />
      </ScrollView>

      <FolhaInferior
        visivel={selecionado !== null}
        aoFechar={fechar}
        titulo={selecionado?.titulo}
        testID="folha-beneficio"
      >
        {selecionado ? (
          <DetalheBeneficio
            beneficio={selecionado}
            saldo={saldo}
            confirmando={confirmando}
            resgatando={resgatar.isPending}
            resultado={resultado ?? null}
            erro={erroResgate ?? null}
            aoPedirConfirmacao={() => setConfirmando(true)}
            aoCancelar={() => setConfirmando(false)}
            aoConfirmar={() =>
              resgatar.mutate({
                beneficioId: selecionado.id,
                chaveIdempotencia: chave.current,
              })
            }
            aoFechar={fechar}
          />
        ) : null}
      </FolhaInferior>
    </SafeAreaView>
  );
}

function ConteudoCatalogo({
  carregando,
  erro,
  aoTentarDeNovo,
  beneficios,
  saldo,
  aoAbrir,
}: {
  carregando: boolean;
  erro: string | null;
  aoTentarDeNovo: () => void;
  beneficios: BeneficioResponse[];
  saldo: number;
  aoAbrir: (b: BeneficioResponse) => void;
}) {
  if (carregando) {
    return (
      <View accessibilityRole="progressbar" accessibilityLabel="Carregando benefícios do bairro">
        <Esqueleto largura="100%" altura={120} />
        <Esqueleto largura="100%" altura={120} />
      </View>
    );
  }

  if (erro) {
    return (
      <EstadoVazio
        testID="beneficios-erro-catalogo"
        titulo="Não deu para carregar o catálogo"
        descricao={erro}
        acao={{ rotulo: 'Tentar de novo', onPress: aoTentarDeNovo }}
      />
    );
  }

  if (beneficios.length === 0) {
    // Estado vazio que ENSINA: diz o que aconteceu e o que fazer a respeito. "Nenhum resultado"
    // sozinho deixa a pessoa achando que o app quebrou.
    return (
      <EstadoVazio
        testID="beneficios-vazio"
        titulo="Nenhum benefício disponível no seu bairro ainda"
        descricao="Os parceiros são comércios locais que aceitam token. Fale com a sua tribo sobre quem poderia entrar — e conclua missões enquanto isso, para chegar com saldo."
      />
    );
  }

  return (
    <>
      {beneficios.map((beneficio) => (
        <CartaoBeneficio
          key={beneficio.id}
          beneficio={beneficio}
          saldo={saldo}
          onPress={() => aoAbrir(beneficio)}
        />
      ))}
    </>
  );
}

function DetalheBeneficio({
  beneficio,
  saldo,
  confirmando,
  resgatando,
  resultado,
  erro,
  aoPedirConfirmacao,
  aoCancelar,
  aoConfirmar,
  aoFechar,
}: {
  beneficio: BeneficioResponse;
  saldo: number;
  confirmando: boolean;
  resgatando: boolean;
  resultado: { codigoRetirada: string; saldoTokensRestante: number } | null;
  erro: ErroApi | null;
  aoPedirConfirmacao: () => void;
  aoCancelar: () => void;
  aoConfirmar: () => void;
  aoFechar: () => void;
}) {
  const estado = estadoDoResgate(saldo, beneficio.custoTokens);

  if (resultado) {
    return (
      <View style={estilos.detalhe}>
        <Text style={estilos.sucesso} accessibilityRole="header">
          Resgate concluído
        </Text>
        <Text style={estilos.descricao}>
          Mostre este código no balcão de {beneficio.parceiroNome}.
        </Text>

        {/*
          O código é lido CARACTERE A CARACTERE. Sem isto o leitor de tela pronuncia "CVYU5UCH" como
          se fosse palavra, e quem está no balcão não consegue repetir o que ouviu.
        */}
        <Text
          style={estilos.codigo}
          accessibilityRole="text"
          accessibilityLabel={`Código de retirada: ${soletrar(resultado.codigoRetirada)}`}
          testID="codigo-retirada"
        >
          {resultado.codigoRetirada}
        </Text>

        <View style={estilos.linhaCusto}>
          <Text style={estilos.rotuloCusto}>Saldo restante</Text>
          <SaldoToken tokens={resultado.saldoTokensRestante} testID="saldo-apos-resgate" />
        </View>

        <Botao titulo="Fechar" onPress={aoFechar} testID="botao-fechar-beneficio" />
      </View>
    );
  }

  return (
    <View style={estilos.detalhe}>
      <Text style={estilos.parceiro}>{beneficio.parceiroNome}</Text>
      <Text style={estilos.descricao}>{beneficio.descricao}</Text>

      <View style={estilos.linhaCusto}>
        <Text style={estilos.rotuloCusto}>Custo</Text>
        <SaldoToken tokens={beneficio.custoTokens} testID="custo-beneficio" />
      </View>

      {erro ? (
        <Text style={estilos.erro} accessibilityRole="alert" testID="erro-resgate">
          {mensagemDoResgate(erro, estado.faltam)}
        </Text>
      ) : null}

      {!estado.alcanca ? (
        <Text style={estilos.falta} accessibilityRole="text">
          Faltam {estado.faltam} tokens para este benefício. Conclua missões da sua tribo para
          chegar lá.
        </Text>
      ) : confirmando ? (
        <>
          <Text style={estilos.confirmacao} accessibilityRole="text">
            Confirmar o resgate de {beneficio.custoTokens} tokens? Eles saem de circulação e não
            voltam.
          </Text>
          <Botao
            titulo={
              resgatando ? 'Resgatando…' : `Confirmar resgate de ${beneficio.custoTokens} tokens`
            }
            onPress={aoConfirmar}
            disabled={resgatando}
            testID="botao-confirmar-resgate"
          />
          <Botao
            titulo="Cancelar"
            variante="texto"
            onPress={aoCancelar}
            disabled={resgatando}
            testID="botao-cancelar-resgate"
          />
        </>
      ) : (
        // Confirmação EXPLÍCITA antes de debitar: um toque só não pode queimar moeda.
        <Botao
          titulo={`Resgatar por ${beneficio.custoTokens} tokens`}
          onPress={aoPedirConfirmacao}
          testID="botao-resgatar"
        />
      )}
    </View>
  );
}

function CartaoBeneficio({
  beneficio,
  saldo,
  onPress,
}: {
  beneficio: BeneficioResponse;
  saldo: number;
  onPress: () => void;
}) {
  const estado = estadoDoResgate(saldo, beneficio.custoTokens);

  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={`${beneficio.titulo}, ${beneficio.parceiroNome}, ${beneficio.custoTokens} tokens`}
      accessibilityHint={
        estado.alcanca
          ? 'Abre os detalhes para resgatar'
          : `Faltam ${estado.faltam} tokens. Abre os detalhes`
      }
      // hitSlop leva o alvo aos 44 pt que a WCAG pede — a auditoria mobile (L4) achou dois
      // elementos a 42 pt neste app, e a correção é esta.
      hitSlop={8}
      testID={`beneficio-${beneficio.id}`}
    >
      <Card estilo={estilos.cartao}>
        <Text style={estilos.parceiro}>{beneficio.parceiroNome}</Text>
        <Text style={estilos.tituloBeneficio}>{beneficio.titulo}</Text>

        <View style={estilos.linhaCusto}>
          <SaldoToken tokens={beneficio.custoTokens} />
          {/*
            Verde é `verdeEscuro` e âmbar é `textoAcessivel.ambar`: as duas cores de MARCA reprovam
            em WCAG AA como texto. Ver a regra em apps/mobile/CLAUDE.md.
          */}
          {estado.alcanca ? (
            <Text style={estilos.alcanca} testID={`estado-${beneficio.id}`}>
              Você já alcança
            </Text>
          ) : (
            <Text style={estilos.falta} testID={`estado-${beneficio.id}`}>
              Faltam {estado.faltam} tokens
            </Text>
          )}
        </View>

        <BarraProgresso
          valor={saldo}
          meta={beneficio.custoTokens}
          cor={estado.alcanca ? cores.verdePrimario : cores.ambar}
          rotuloAcessivel={
            estado.alcanca
              ? 'Saldo suficiente para este benefício'
              : `Faltam ${estado.faltam} tokens para este benefício`
          }
          testID={`progresso-${beneficio.id}`}
        />
      </Card>
    </Pressable>
  );
}

/**
 * A frase do erro, discriminada por `type` — NUNCA por `detail`.
 *
 * Saldo insuficiente chega como `regraNegocioViolada`, um 422 genérico sem campos estruturados. O
 * "faltam N" é calculado AQUI, com dois números que o app já tem: o saldo da carteira e o custo do
 * catálogo. Parsear o `detail` do servidor daria o mesmo texto e acoplaria a tela à revisão de copy
 * do backend — que é exatamente o que a regra dura de `src/api/erros.ts` proíbe.
 */
function mensagemDoResgate(erro: ErroApi, faltam: number): string {
  if (erro.tipo === 'regraNegocioViolada') {
    return faltam > 0
      ? `Saldo insuficiente: faltam ${faltam} tokens. Conclua missões da sua tribo para chegar lá.`
      : 'Este benefício não está mais disponível. Puxe para atualizar o catálogo.';
  }
  if (erro.tipo === 'naoEncontrado') {
    return 'Este benefício saiu do catálogo. Puxe para atualizar.';
  }
  return mensagemDe(erro);
}

/** "CVYU5UCH" → "C, V, Y, U, 5, U, C, H" — para o leitor de tela não pronunciar como palavra. */
function soletrar(codigo: string): string {
  return codigo.split('').join(', ');
}

const estilos = StyleSheet.create({
  raiz: { flex: 1, backgroundColor: cores.papel },
  corpo: { padding: espaco.lg, gap: espaco.md },
  cabecalho: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  titulo: { ...tipografia.titulo, color: cores.tinta },
  saldo: { alignItems: 'flex-start' },
  rotuloSaldo: { ...tipografia.rotulo, color: cores.tinta70 },
  explicacao: { ...tipografia.legenda, color: cores.tinta70 },
  cartao: { gap: espaco.sm },
  parceiro: { ...tipografia.legenda, color: cores.tinta70 },
  tituloBeneficio: { ...tipografia.subtitulo, color: cores.tinta },
  linhaCusto: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  rotuloCusto: { ...tipografia.rotulo, color: cores.tinta70 },
  alcanca: { ...tipografia.rotulo, color: cores.verdeEscuro },
  falta: { ...tipografia.rotulo, color: textoAcessivel.ambar },
  detalhe: { gap: espaco.md },
  descricao: { ...tipografia.corpo, color: cores.tinta },
  confirmacao: { ...tipografia.corpo, color: cores.tinta },
  sucesso: { ...tipografia.subtitulo, color: cores.verdeEscuro },
  codigo: { ...tipografia.titulo, color: cores.tinta, letterSpacing: 4, textAlign: 'center' },
  erro: { ...tipografia.rotulo, color: textoAcessivel.coral },
});
