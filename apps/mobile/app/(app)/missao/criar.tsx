import { zodResolver } from '@hookform/resolvers/zod';
import { useRouter } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { mensagemDe } from '@/api/erros';
import { CATEGORIAS, COMPLEXIDADES, ROTULO_COMPLEXIDADE } from '@/features/missoes/rotulos';
import type { CriarMissaoRequest } from '@/api/tipos';
import { Aviso } from '@/components/Aviso';
import { Botao } from '@/components/Botao';
import { CampoTexto } from '@/components/CampoTexto';
import { Card } from '@/components/Card';
import { Chip } from '@/components/Chip';
import { FolhaInferior } from '@/components/FolhaInferior';
import { MapaLeaflet } from '@/components/MapaLeaflet';
import { SaldoToken } from '@/components/SaldoToken';
import { SeletorDataHora } from '@/components/SeletorDataHora';
import { useEnderecoPorCep, usePontosCustodiaProximos } from '@/features/mapa/hooks';
import { useCriarMissao, usePreviaRecompensa } from '@/features/missoes/hooks';
import { useLocalizacao } from '@/features/missoes/useLocalizacao';
import { useDebounce } from '@/lib/debounce';
import { rotuloCategoria } from '@/lib/formatar';
import { criarMissaoSchema, type CriarMissaoForm } from '@/schemas';
import { cores, coresCategoria, espaco, textoAcessivel, tipografia } from '@/theme';

export default function CriarMissao() {
  const router = useRouter();
  const criar = useCriarMissao();
  // Sem pedido automático: a origem é escolhida no mapa, que é o que a especificação pede. Um
  // prompt de permissão ao abrir "Criar missão" seria o mesmo defeito da aba de missões.
  const { coordenada } = useLocalizacao(false);
  const [mapaAberto, setMapaAberto] = useState(false);
  const [pontosAberto, setPontosAberto] = useState(false);

  const agora = useMemo(() => new Date(), []);
  const emUmDia = useMemo(() => new Date(Date.now() + 24 * 3600_000), []);

  const {
    control,
    handleSubmit,
    watch,
    setValue,
    formState: { errors },
  } = useForm<CriarMissaoForm>({
    resolver: zodResolver(criarMissaoSchema),
    // `onChange`: a prévia de recompensa depende de um formulário VÁLIDO, e validar só no envio
    // deixaria o card de recompensa vazio até o último toque.
    mode: 'onChange',
    defaultValues: {
      categoria: 'AJUDA',
      titulo: '',
      descricao: '',
      cep: '',
      logradouro: '',
      bairro: '',
      cidade: '',
      uf: '',
      raioCheckinM: 50,
      janelaInicio: agora,
      janelaFim: emUmDia,
      origemLat: coordenada?.lat ?? -23.5505,
      origemLon: coordenada?.lon ?? -46.6333,
    },
  });

  const valores = watch();
  const movimentaObjeto = valores.categoria === 'ENTREGA' || valores.categoria === 'COLETA';

  // Assim que o GPS responde, a origem passa a ser onde a pessoa está — sem sobrescrever um ponto
  // que ela já tenha escolhido no mapa.
  const [origemTocada, setOrigemTocada] = useState(false);
  useEffect(() => {
    if (coordenada && !origemTocada) {
      setValue('origemLat', coordenada.lat);
      setValue('origemLon', coordenada.lon);
    }
  }, [coordenada, origemTocada, setValue]);

  // ─── CEP com debounce de 500 ms ─────────────────────────────────────────────────────────────
  const cepAtrasado = useDebounce(valores.cep ?? '', 500);
  const endereco = useEnderecoPorCep(cepAtrasado);

  useEffect(() => {
    if (!endereco.data) return;
    // Preenche, e deixa editável: o endereço do CEP é conveniência, não verdade. Número e
    // complemento não vêm do provedor, e é o usuário quem completa.
    setValue('logradouro', endereco.data.logradouro || '', { shouldValidate: true });
    setValue('bairro', endereco.data.bairro || '', { shouldValidate: true });
    setValue('cidade', endereco.data.cidade || '', { shouldValidate: true });
    setValue('uf', endereco.data.uf || '', { shouldValidate: true });
  }, [endereco.data, setValue]);

  // ─── Prévia com debounce de 400 ms ──────────────────────────────────────────────────────────
  //
  // O corpo só é montado quando o formulário está inteiro válido: mandar um payload incompleto
  // renderia 400 a cada tecla, e o card piscaria erro enquanto a pessoa digita.
  const corpoParaPrevia = useMemo<CriarMissaoRequest | null>(() => {
    const resultado = criarMissaoSchema.safeParse(valores);
    return resultado.success ? paraRequest(resultado.data) : null;
  }, [valores]);
  const corpoAtrasado = useDebounce(corpoParaPrevia, 400);
  const previa = usePreviaRecompensa(corpoAtrasado);

  const pontos = usePontosCustodiaProximos(
    valores.origemLat && valores.origemLon
      ? { lat: valores.origemLat, lon: valores.origemLon }
      : null,
  );

  function enviar(dados: CriarMissaoForm) {
    criar.mutate(paraRequest(dados), {
      onSuccess: (missao) => router.replace(`/missao/${missao.id}`),
    });
  }

  return (
    <SafeAreaView style={estilos.raiz} testID="tela-criar-missao">
      <ScrollView contentContainerStyle={estilos.conteudo} keyboardShouldPersistTaps="handled">
        <Text style={estilos.titulo} accessibilityRole="header">
          Nova missão
        </Text>

        {/* ─── Categoria ─────────────────────────────────────────────────────────────────── */}
        <Text style={estilos.rotulo}>Categoria</Text>
        <View style={estilos.chips}>
          {CATEGORIAS.map((categoria) => {
            const paleta = coresCategoria[categoria];
            const selecionada = valores.categoria === categoria;
            return (
              <Chip
                key={categoria}
                rotulo={rotuloCategoria(categoria)}
                selecionado={selecionada}
                corFundo={paleta.fundo}
                corTexto={paleta.texto}
                onPress={() => {
                  setValue('categoria', categoria, { shouldValidate: true });
                  // Trocar de categoria muda QUAIS campos são válidos: entrega e coleta exigem peso
                  // e volume e proíbem complexidade declarada; tribo e ajuda o oposto. Limpar
                  // evita mandar a combinação que o servidor recusa com 400.
                  setValue('pesoKg', undefined);
                  setValue('volumeL', undefined);
                  setValue('complexidade', undefined, { shouldValidate: true });
                }}
                testID={`categoria-${categoria}`}
              />
            );
          })}
        </View>

        <Controller
          control={control}
          name="titulo"
          render={({ field }) => (
            <CampoTexto
              rotulo="Título"
              value={field.value}
              onChangeText={field.onChange}
              erro={errors.titulo?.message}
              testID="campo-titulo"
            />
          )}
        />

        <Controller
          control={control}
          name="descricao"
          render={({ field }) => (
            <CampoTexto
              rotulo="Descrição"
              value={field.value}
              onChangeText={field.onChange}
              multiline
              erro={errors.descricao?.message}
              testID="campo-descricao"
            />
          )}
        />

        {/* ─── Esforço: derivado ou declarado, nunca os dois ──────────────────────────────── */}
        {movimentaObjeto ? (
          <Card>
            <Text style={estilos.rotulo}>Peso e volume</Text>
            <Text style={estilos.ajuda}>
              A complexidade é calculada a partir destes dois valores — não há campo para
              informá-la.
            </Text>
            <Controller
              control={control}
              name="pesoKg"
              render={({ field }) => (
                <CampoTexto
                  rotulo="Peso (kg)"
                  keyboardType="decimal-pad"
                  value={field.value?.toString() ?? ''}
                  onChangeText={(texto) => field.onChange(numeroOuIndefinido(texto))}
                  erro={errors.pesoKg?.message}
                  testID="campo-peso"
                />
              )}
            />
            <Controller
              control={control}
              name="volumeL"
              render={({ field }) => (
                <CampoTexto
                  rotulo="Volume (litros)"
                  keyboardType="decimal-pad"
                  value={field.value?.toString() ?? ''}
                  onChangeText={(texto) => field.onChange(numeroOuIndefinido(texto))}
                  erro={errors.volumeL?.message}
                  testID="campo-volume"
                />
              )}
            />
          </Card>
        ) : (
          <View>
            <Text style={estilos.rotulo}>Complexidade</Text>
            <View style={estilos.chips}>
              {COMPLEXIDADES.map((nivel) => (
                <Chip
                  key={nivel}
                  rotulo={ROTULO_COMPLEXIDADE[nivel]}
                  selecionado={valores.complexidade === nivel}
                  onPress={() => setValue('complexidade', nivel, { shouldValidate: true })}
                  testID={`complexidade-${nivel}`}
                />
              ))}
            </View>
            {errors.complexidade ? (
              <Text style={estilos.erro} accessibilityLiveRegion="polite">
                {errors.complexidade.message}
              </Text>
            ) : null}
          </View>
        )}

        {/* ─── Recompensa: LEITURA, nunca entrada ─────────────────────────────────────────── */}
        <Card estilo={estilos.cardRecompensa}>
          <Text style={estilos.rotulo}>Recompensa calculada</Text>
          {previa.data ? (
            <View style={estilos.recompensa} testID="previa-recompensa">
              <Text style={estilos.xp}>{previa.data.xpRecompensa} XP</Text>
              <SaldoToken tokens={previa.data.tokensRecompensa} />
            </View>
          ) : previa.isError ? (
            // Falhar a prévia NÃO bloqueia criar: ela é informativa, e o valor definitivo é
            // congelado pelo servidor na publicação de qualquer forma.
            <Text style={estilos.ajuda} testID="previa-indisponivel">
              A recompensa será calculada ao publicar.
            </Text>
          ) : (
            <Text style={estilos.ajuda}>
              Preencha os campos para ver quanto esta missão vai valer.
            </Text>
          )}
          <Text style={estilos.ajuda}>
            Quem cria a missão não paga. O valor é calculado pelo servidor e congelado na criação.
          </Text>
        </Card>

        {/* ─── Endereço ──────────────────────────────────────────────────────────────────── */}
        <Controller
          control={control}
          name="cep"
          render={({ field }) => (
            <CampoTexto
              rotulo="CEP"
              keyboardType="number-pad"
              maxLength={8}
              value={field.value}
              onChangeText={(texto) => field.onChange(texto.replace(/\D/g, ''))}
              erro={errors.cep?.message ?? (endereco.isError ? 'CEP não encontrado.' : null)}
              testID="campo-cep"
            />
          )}
        />

        {(['logradouro', 'bairro', 'cidade', 'uf'] as const).map((campo) => (
          <Controller
            key={campo}
            control={control}
            name={campo}
            render={({ field }) => (
              <CampoTexto
                rotulo={campo === 'uf' ? 'UF' : campo[0].toUpperCase() + campo.slice(1)}
                value={field.value}
                autoCapitalize={campo === 'uf' ? 'characters' : 'sentences'}
                maxLength={campo === 'uf' ? 2 : undefined}
                onChangeText={(texto) =>
                  field.onChange(campo === 'uf' ? texto.toUpperCase() : texto)
                }
                erro={errors[campo]?.message}
                testID={`campo-${campo}`}
              />
            )}
          />
        ))}

        <Botao
          titulo="Escolher ponto no mapa"
          variante="secundario"
          onPress={() => setMapaAberto(true)}
          testID="botao-escolher-ponto"
        />
        <Text style={estilos.ajuda} testID="coordenada-escolhida">
          Ponto: {valores.origemLat?.toFixed(5)}, {valores.origemLon?.toFixed(5)}
        </Text>

        {/* ─── Ponto de custódia, opcional ───────────────────────────────────────────────── */}
        <Botao
          titulo={
            valores.pontoCustodiaId
              ? (pontos.data?.find((p) => p.id === valores.pontoCustodiaId)?.apelido ??
                'Ponto escolhido')
              : 'Ponto de custódia (opcional)'
          }
          variante="secundario"
          onPress={() => setPontosAberto(true)}
          testID="botao-ponto-custodia"
        />

        {/* ─── Janela ────────────────────────────────────────────────────────────────────── */}
        <Controller
          control={control}
          name="janelaInicio"
          render={({ field }) => (
            <SeletorDataHora
              rotulo="Início da janela"
              valor={field.value}
              aoMudar={field.onChange}
              erro={errors.janelaInicio?.message}
              testID="campo-janela-inicio"
            />
          )}
        />
        <Controller
          control={control}
          name="janelaFim"
          render={({ field }) => (
            <SeletorDataHora
              rotulo="Fim da janela"
              valor={field.value}
              aoMudar={field.onChange}
              minimo={valores.janelaInicio}
              erro={errors.janelaFim?.message}
              testID="campo-janela-fim"
            />
          )}
        />

        {criar.error ? (
          <Aviso tom="erro" mensagem={mensagemDe(criar.error)} testID="erro-criar" />
        ) : null}

        <Botao
          titulo="Criar missão"
          carregando={criar.isPending}
          onPress={handleSubmit(enviar)}
          testID="botao-criar"
        />
        <Text style={estilos.ajuda}>
          A missão nasce como rascunho. Publicar é o próximo passo, no detalhe dela.
        </Text>
      </ScrollView>

      <FolhaInferior
        visivel={mapaAberto}
        aoFechar={() => setMapaAberto(false)}
        titulo="Toque no ponto da missão"
        testID="folha-mapa"
      >
        <View style={estilos.mapaSeletor}>
          <MapaLeaflet
            centro={{ lat: valores.origemLat, lon: valores.origemLon }}
            marcadores={[
              {
                id: 'origem',
                lat: valores.origemLat,
                lon: valores.origemLon,
                cor: coresCategoria[valores.categoria].texto,
                forma: 'pino',
                rotulo: 'Origem da missão',
              },
            ]}
            aoTocarMapa={(lat, lon) => {
              setOrigemTocada(true);
              setValue('origemLat', lat, { shouldValidate: true });
              setValue('origemLon', lon, { shouldValidate: true });
            }}
            testID="mapa-seletor"
          />
        </View>
        <Botao titulo="Usar este ponto" onPress={() => setMapaAberto(false)} />
      </FolhaInferior>

      <FolhaInferior
        visivel={pontosAberto}
        aoFechar={() => setPontosAberto(false)}
        titulo="Ponto de custódia"
        testID="folha-pontos"
      >
        <Botao
          titulo="Sem ponto de custódia"
          variante="secundario"
          onPress={() => {
            setValue('pontoCustodiaId', undefined);
            setPontosAberto(false);
          }}
        />
        {(pontos.data ?? []).map((ponto) => (
          <Botao
            key={ponto.id}
            titulo={`${ponto.apelido} · ${ponto.tipo.toLowerCase()}`}
            variante="secundario"
            onPress={() => {
              setValue('pontoCustodiaId', ponto.id, { shouldValidate: true });
              setPontosAberto(false);
            }}
            testID={`ponto-${ponto.codigo}`}
          />
        ))}
      </FolhaInferior>
    </SafeAreaView>
  );
}

/**
 * Converte o formulário no corpo da API.
 *
 * **`valorBrl: 0` é explícito e obrigatório.** O campo é `@NotNull` no servidor, e qualquer valor
 * maior é recusado com 400 — não existe missão remunerada em reais (ADR 0009). E não há aqui
 * NENHUM campo de recompensa: `xpRecompensa` e `tokensRecompensa` são calculados e congelados pelo
 * servidor; mandá-los seria silenciosamente ignorado, e o criador veria um número na tela e outro
 * na missão publicada.
 */
function paraRequest(dados: CriarMissaoForm): CriarMissaoRequest {
  return {
    categoria: dados.categoria,
    titulo: dados.titulo,
    descricao: dados.descricao,
    valorBrl: 0,
    complexidade: dados.complexidade,
    origemLat: dados.origemLat,
    origemLon: dados.origemLon,
    cep: dados.cep,
    logradouro: dados.logradouro,
    bairro: dados.bairro,
    cidade: dados.cidade,
    uf: dados.uf,
    raioCheckinM: dados.raioCheckinM,
    pesoKg: dados.pesoKg,
    volumeL: dados.volumeL,
    janelaInicio: dados.janelaInicio.toISOString(),
    janelaFim: dados.janelaFim.toISOString(),
    pontoCustodiaId: dados.pontoCustodiaId,
  };
}

/** Campo numérico vazio vira `undefined`, não `0` — "não informado" e "zero" são coisas diferentes. */
function numeroOuIndefinido(texto: string): number | undefined {
  const limpo = texto.replace(',', '.');
  if (limpo.trim() === '') return undefined;
  const numero = Number(limpo);
  return Number.isFinite(numero) ? numero : undefined;
}

const estilos = StyleSheet.create({
  raiz: { flex: 1, backgroundColor: cores.papel },
  conteudo: { padding: espaco.lg, gap: espaco.md },
  titulo: { ...tipografia.titulo, color: cores.tinta },
  rotulo: { ...tipografia.rotulo, color: cores.tinta70 },
  ajuda: { ...tipografia.legenda, color: textoAcessivel.suave },
  erro: { ...tipografia.legenda, color: textoAcessivel.coral },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: espaco.sm },
  cardRecompensa: { backgroundColor: cores.verdeClaro, gap: espaco.xs },
  recompensa: { flexDirection: 'row', alignItems: 'center', gap: espaco.lg },
  xp: { ...tipografia.subtitulo, color: textoAcessivel.ambar },
  mapaSeletor: { height: 320 },
});
