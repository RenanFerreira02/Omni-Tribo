import { useRouter } from 'expo-router';
import { useMemo, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mensagemDe } from '@/api/erros';
import { Aviso } from '@/components/Aviso';
import { BarraProgresso } from '@/components/BarraProgresso';
import { Botao } from '@/components/Botao';
import { Card } from '@/components/Card';
import { Chip } from '@/components/Chip';
import { Esqueleto } from '@/components/Esqueleto';
import { EstadoVazio } from '@/components/EstadoVazio';
import { FolhaInferior } from '@/components/FolhaInferior';
import { SaldoToken } from '@/components/SaldoToken';
import { useCarteira } from '@/features/carteira/hooks';
import { BENEFICIOS, estadoDoResgate, type Beneficio } from '@/features/beneficios/catalogo';
import { cores, espaco, textoAcessivel, tipografia } from '@/theme';

/**
 * Para que serve o token.
 *
 * Esta tela substitui o botão "Sacar em reais", que era visível-desabilitado com a explicação ao
 * lado. Aquela escolha tinha uma razão registrada — "um botão ausente não ensina nada" —, e ela
 * envelheceu: o app promete resgate em benefício de parceiro em três lugares (o card da carteira,
 * `SaldoToken` e o onboarding) e não tinha porta nenhuma para isso. Um aviso explica o que a moeda
 * NÃO é; um catálogo mostra o que ela É. A segunda coisa ensina mais, e é a tese do produto.
 *
 * **Nada aqui debita saldo, e a tela diz isso ao usuário.** O resgate é o sumidouro do TOKEN
 * (ADR 0009 §3) e o backend ainda não o tem — ver o cabeçalho de `features/beneficios/catalogo.ts`
 * para o inventário do que falta. Simular o débito só no cliente produziria um saldo que o servidor
 * desmente no primeiro `refetch`, e um número que muda sozinho contamina a confiança na carteira
 * inteira. Preferimos uma vitrine honesta a uma transação de mentira.
 */
export default function TelaBeneficios() {
  const router = useRouter();
  const carteira = useCarteira();
  const [soAlcancaveis, setSoAlcancaveis] = useState(false);
  const [selecionado, setSelecionado] = useState<Beneficio | null>(null);

  const saldo = carteira.data?.saldoTokens ?? 0;

  // O filtro depende do saldo, que chega por rede: memo evita refiltrar a cada renderização da
  // folha, que abre e fecha sem que o catálogo mude.
  const visiveis = useMemo(
    () =>
      soAlcancaveis
        ? BENEFICIOS.filter((b) => estadoDoResgate(saldo, b.custoTokens).alcanca)
        : BENEFICIOS,
    [soAlcancaveis, saldo],
  );

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
          {/*
            A frase é a tese do ADR 0009 §3 dita ao usuário: o resgate é o SUMIDOURO da moeda. É o
            que diferencia token de pontuação — pontos só sobem; moeda comunitária sai de circulação
            quando vira algo no mundo.
          */}
          <Text style={estilos.explicacao}>
            Tokens resgatados saem de circulação. É assim que a moeda da tribo vira algo concreto no
            bairro.
          </Text>
        </Card>

        <View style={estilos.filtros}>
          <Chip
            rotulo="Tudo"
            selecionado={!soAlcancaveis}
            onPress={() => setSoAlcancaveis(false)}
            testID="filtro-tudo"
          />
          <Chip
            rotulo="Já alcanço"
            selecionado={soAlcancaveis}
            onPress={() => setSoAlcancaveis(true)}
            testID="filtro-alcancaveis"
          />
        </View>

        {visiveis.length === 0 ? (
          <EstadoVazio
            testID="beneficios-vazio"
            titulo="Ainda não dá para resgatar nada"
            descricao="Conclua missões da sua tribo para juntar tokens. O catálogo continua aqui."
            acao={{ rotulo: 'Ver tudo', onPress: () => setSoAlcancaveis(false) }}
          />
        ) : (
          visiveis.map((beneficio) => (
            <CartaoBeneficio
              key={beneficio.id}
              beneficio={beneficio}
              saldo={saldo}
              onPress={() => setSelecionado(beneficio)}
            />
          ))
        )}
      </ScrollView>

      <FolhaInferior
        visivel={selecionado !== null}
        aoFechar={() => setSelecionado(null)}
        titulo={selecionado?.titulo}
        testID="folha-beneficio"
      >
        {selecionado ? (
          <View style={estilos.detalhe}>
            <Text style={estilos.parceiro}>{selecionado.parceiro}</Text>
            <Text style={estilos.descricao}>{selecionado.descricao}</Text>

            <View style={estilos.linhaCusto}>
              <Text style={estilos.rotuloCusto}>Custo</Text>
              <SaldoToken tokens={selecionado.custoTokens} testID="custo-beneficio" />
            </View>

            {/*
              O aviso diz a verdade sobre o estado do produto, em vez de um botão morto. Repetir
              "botão desabilitado + motivo" seria trocar um não por outro — e era exatamente o
              padrão que esta tela veio substituir.
            */}
            <Aviso
              tom="informacao"
              titulo="Como funciona hoje"
              mensagem="O resgate é combinado no balcão do parceiro, mostrando seu perfil na tribo. A baixa automática no seu saldo entra junto com a carteira de patrocinador — por isso nada é descontado agora."
              testID="aviso-resgate"
            />

            <Botao
              titulo="Entendi"
              onPress={() => setSelecionado(null)}
              testID="botao-fechar-beneficio"
            />
          </View>
        ) : null}
      </FolhaInferior>
    </SafeAreaView>
  );
}

function CartaoBeneficio({
  beneficio,
  saldo,
  onPress,
}: {
  beneficio: Beneficio;
  saldo: number;
  onPress: () => void;
}) {
  const estado = estadoDoResgate(saldo, beneficio.custoTokens);

  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={`${beneficio.titulo}, ${beneficio.parceiro}, ${beneficio.custoTokens} tokens`}
      testID={`beneficio-${beneficio.id}`}
    >
      <Card estilo={estilos.cartao}>
        <Text style={estilos.parceiro}>{beneficio.parceiro}</Text>
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

const estilos = StyleSheet.create({
  raiz: { flex: 1, backgroundColor: cores.papel },
  corpo: { padding: espaco.lg, gap: espaco.md },
  cabecalho: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  titulo: { ...tipografia.titulo, color: cores.tinta },
  saldo: { alignItems: 'flex-start' },
  rotuloSaldo: { ...tipografia.rotulo, color: cores.tinta70 },
  explicacao: { ...tipografia.legenda, color: cores.tinta70 },
  filtros: { flexDirection: 'row', gap: espaco.sm },
  cartao: { gap: espaco.sm },
  parceiro: { ...tipografia.legenda, color: cores.tinta70 },
  tituloBeneficio: { ...tipografia.subtitulo, color: cores.tinta },
  linhaCusto: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  rotuloCusto: { ...tipografia.rotulo, color: cores.tinta70 },
  alcanca: { ...tipografia.rotulo, color: cores.verdeEscuro },
  falta: { ...tipografia.rotulo, color: textoAcessivel.ambar },
  detalhe: { gap: espaco.md },
  descricao: { ...tipografia.corpo, color: cores.tinta },
});
