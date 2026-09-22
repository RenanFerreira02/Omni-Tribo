import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { ScrollView, StyleSheet, Text, View } from 'react-native';

import type { ErroApi } from '@/api/erros';
import { mensagemDe } from '@/api/erros';
import { CATEGORIAS, COMPLEXIDADES, ROTULO_COMPLEXIDADE } from '@/features/missoes/rotulos';
import type { CategoriaMissao, CriarMissaoRequest } from '@/api/tipos';
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
import { usePreviaRecompensa } from '@/features/missoes/hooks';
import { useLocalizacao } from '@/features/missoes/useLocalizacao';
import { useAnuncio } from '@/lib/anunciar';
import { useDebounce } from '@/lib/debounce';
import { rotuloCategoria, rotuloTipoPonto } from '@/lib/formatar';
import { criarMissaoSchema, type CriarMissaoForm } from '@/schemas';
import {
  cores,
  coresCategoria,
  coresMarcador,
  espaco,
  glifoCategoria,
  textoAcessivel,
  tipografia,
} from '@/theme';

/**
 * Altura do mapa embutido no seletor de ponto.
 *
 * Fora das escalas de propósito, e nomeada em vez de solta: não é respiro nem tipografia — é o
 * VIEWPORT de um mapa, e o número sai da pergunta "quanto de bairro cabe aqui sem o formulário
 * sumir de vista". Pôr isso em `espaco` (cujo maior degrau é 32) seria forçar a escala a significar
 * duas coisas diferentes.
 */
const ALTURA_SELETOR_MAPA = 320;

/**
 * Recompensar em token é o padrão — EXCETO em AJUDA.
 *
 * A assimetria é o argumento do próprio ADR 0025, lido ao contrário. Num mutirão quem financia
 * também se beneficia: o bem é coletivo, e pedir o pote à tribo é coerente. Numa AJUDA o
 * beneficiário é uma pessoa só, e o ADR previu que financiar favor alheio poderia se mostrar pouco
 * atraente e represar a categoria em RASCUNHO — foi o que aconteceu. Então AJUDA nasce publicável.
 *
 * É padrão, não regra: as duas categorias aceitam as duas formas, e o chip está sempre à vista.
 */
export function padraoRecompensaEmToken(categoria: CategoriaMissao): boolean {
  return categoria !== 'AJUDA';
}

export interface FormularioMissaoProps {
  /**
   * `editar` trava a categoria: ela é imutável depois da criação e o `PATCH` nem a declara. Os
   * chips continuam VISÍVEIS, sem `onPress`, porque esconder a categoria da tela de edição
   * deixaria a pessoa sem saber o que está editando.
   */
  modo: 'criar' | 'editar';
  valoresIniciais?: Partial<CriarMissaoForm>;
  rotuloEnvio: string;
  enviando: boolean;
  erro: ErroApi | null;
  aoEnviar: (dados: CriarMissaoForm) => void;
  /** Frase sob o botão. Explica o que acontece DEPOIS do envio, e muda entre criar e editar. */
  nota: string;
  testID?: string;
}

export function FormularioMissao({
  modo,
  valoresIniciais,
  rotuloEnvio,
  enviando,
  erro,
  aoEnviar,
  nota,
  testID,
}: FormularioMissaoProps) {
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
      recompensaEmToken: padraoRecompensaEmToken(valoresIniciais?.categoria ?? 'AJUDA'),
      ...valoresIniciais,
    },
  });

  const valores = watch();
  const movimentaObjeto = valores.categoria === 'ENTREGA' || valores.categoria === 'COLETA';
  const editando = modo === 'editar';

  // Assim que o GPS responde, a origem passa a ser onde a pessoa está — sem sobrescrever um ponto
  // que ela já tenha escolhido no mapa. Na EDIÇÃO não roda: a missão já tem origem, e movê-la para
  // onde o celular está agora apagaria o endereço que a pessoa escolheu antes, sem ela pedir.
  const [origemTocada, setOrigemTocada] = useState(false);
  useEffect(() => {
    if (editando) return;
    if (coordenada && !origemTocada) {
      setValue('origemLat', coordenada.lat);
      setValue('origemLon', coordenada.lon);
    }
  }, [coordenada, origemTocada, setValue, editando]);

  // ─── CEP com debounce de 500 ms ─────────────────────────────────────────────────────────────
  const cepAtrasado = useDebounce(valores.cep ?? '', 500);
  const endereco = useEnderecoPorCep(cepAtrasado);

  /**
   * O CEP com que a tela abriu, para NÃO auto-preencher em cima do que já está lá.
   *
   * Na criação o campo nasce vazio e a busca só dispara quando a pessoa digita. Na EDIÇÃO ele nasce
   * preenchido: sem esta guarda, abrir a tela buscava o CEP na montagem e sobrescrevia logradouro,
   * bairro, cidade e UF com a versão do provedor — apagando o número e o complemento que o usuário
   * tinha completado à mão, sem ele ter tocado em nada. O preenchimento automático é para quando a
   * pessoa INFORMA um CEP, não para quando ela abre a tela.
   */
  const cepInicial = useRef(valoresIniciais?.cep ?? '');

  useEffect(() => {
    if (!endereco.data) return;
    if (cepAtrasado === cepInicial.current) return;
    // Preenche, e deixa editável: o endereço do CEP é conveniência, não verdade. Número e
    // complemento não vêm do provedor, e é o usuário quem completa.
    setValue('logradouro', endereco.data.logradouro || '', { shouldValidate: true });
    setValue('bairro', endereco.data.bairro || '', { shouldValidate: true });
    setValue('cidade', endereco.data.cidade || '', { shouldValidate: true });
    setValue('uf', endereco.data.uf || '', { shouldValidate: true });
  }, [endereco.data, cepAtrasado, setValue]);

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

  /**
   * A prévia recalcula sozinha enquanto a pessoa digita, e o resultado só existia como texto novo
   * dentro de um card — invisível para quem não vê a tela. Deriva do dado (e não de um estado
   * próprio) de propósito: o `useAnuncio` só fala quando a frase MUDA, então re-renderizações com o
   * mesmo valor não viram eco, e cada novo cálculo é dito uma vez.
   *
   * Zero token tem frase PRÓPRIA. "Recompensa calculada: 90 XP e 0 tokens" descreve um erro; o que
   * está acontecendo é uma escolha, e o leitor de tela precisa ouvir a escolha.
   */
  useAnuncio(
    previa.data
      ? previa.data.tokensRecompensa === 0
        ? `Recompensa calculada: ${previa.data.xpRecompensa} XP, sem tokens.`
        : `Recompensa calculada: ${previa.data.xpRecompensa} XP e ${previa.data.tokensRecompensa} tokens.`
      : null,
  );

  const pontos = usePontosCustodiaProximos(
    valores.origemLat && valores.origemLon
      ? { lat: valores.origemLat, lon: valores.origemLon }
      : null,
  );

  return (
    <>
      <ScrollView contentContainerStyle={estilos.conteudo} keyboardShouldPersistTaps="handled">
        {/* ─── Categoria ─────────────────────────────────────────────────────────────────── */}
        <Text style={estilos.rotulo}>Categoria</Text>
        <View
          style={estilos.chips}
          accessibilityRole="radiogroup"
          accessibilityLabel="Categoria da missão"
        >
          {CATEGORIAS.map((categoria) => {
            const paleta = coresCategoria[categoria];
            const selecionada = valores.categoria === categoria;
            // Na edição os chips não respondem ao toque: a categoria é imutável e o PATCH não a
            // declara. Um chip que parece clicável e não faz nada é pior que um chip inerte.
            if (editando && !selecionada) return null;
            return (
              <Chip
                key={categoria}
                rotulo={rotuloCategoria(categoria)}
                glifo={glifoCategoria[categoria]}
                selecionado={selecionada}
                corFundo={paleta.fundo}
                corTexto={paleta.texto}
                onPress={
                  editando
                    ? undefined
                    : () => {
                        setValue('categoria', categoria, { shouldValidate: true });
                        // Trocar de categoria muda QUAIS campos são válidos: entrega e coleta
                        // exigem peso e volume e proíbem complexidade declarada; tribo e ajuda o
                        // oposto. Limpar evita mandar a combinação que o servidor recusa com 400.
                        setValue('pesoKg', undefined);
                        setValue('volumeL', undefined);
                        setValue('complexidade', undefined, { shouldValidate: true });
                        // E a forma de recompensa reassume o padrão da categoria nova: entrega e
                        // coleta só aceitam token, e manter "só XP" aqui montaria um corpo que o
                        // servidor recusa com 400.
                        setValue('recompensaEmToken', padraoRecompensaEmToken(categoria), {
                          shouldValidate: true,
                        });
                      }
                }
                testID={`categoria-${categoria}`}
              />
            );
          })}
        </View>
        {editando ? (
          <Text style={estilos.ajuda}>A categoria não muda depois que a missão é criada.</Text>
        ) : null}

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
            <View
              style={estilos.chips}
              accessibilityRole="radiogroup"
              accessibilityLabel="Complexidade da missão"
            >
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

        {/* ─── Forma de recompensa: só TRIBO e AJUDA escolhem ─────────────────────────────── */}
        {movimentaObjeto ? null : (
          <View>
            <Text style={estilos.rotulo}>Como esta missão recompensa</Text>
            <View
              style={estilos.chips}
              accessibilityRole="radiogroup"
              accessibilityLabel="Forma de recompensa da missão"
            >
              <Chip
                rotulo="Só XP"
                selecionado={!valores.recompensaEmToken}
                onPress={() => setValue('recompensaEmToken', false, { shouldValidate: true })}
                testID="recompensa-so-xp"
              />
              <Chip
                rotulo="XP e tokens"
                selecionado={valores.recompensaEmToken}
                onPress={() => setValue('recompensaEmToken', true, { shouldValidate: true })}
                testID="recompensa-com-token"
              />
            </View>
            <Text style={estilos.ajuda} testID="explicacao-recompensa">
              {valores.recompensaEmToken
                ? 'Publica quando um vizinho da sua tribo financiar o pote. Quem cria nunca paga.'
                : 'Publica na hora. O executor ganha reputação, e nenhum token é movido.'}
            </Text>
          </View>
        )}

        {/* ─── Recompensa: LEITURA, nunca entrada ─────────────────────────────────────────── */}
        <Card estilo={estilos.cardRecompensa}>
          <Text style={estilos.rotulo}>Recompensa calculada</Text>
          {previa.data ? (
            <View
              style={estilos.recompensa}
              testID="previa-recompensa"
              accessible
              accessibilityLabel={
                previa.data.tokensRecompensa === 0
                  ? `Recompensa calculada: ${previa.data.xpRecompensa} XP, sem tokens`
                  : `Recompensa calculada: ${previa.data.xpRecompensa} XP e ${previa.data.tokensRecompensa} tokens`
              }
            >
              <Text style={estilos.xp}>{previa.data.xpRecompensa} XP</Text>
              {previa.data.tokensRecompensa > 0 ? (
                <SaldoToken tokens={previa.data.tokensRecompensa} />
              ) : (
                // "0" ao lado do ícone de token leria como falha de cálculo. Sem token é uma
                // escolha, e a frase diz isso.
                <Text style={estilos.ajuda} testID="previa-sem-token">
                  sem tokens
                </Text>
              )}
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

        {erro ? <Aviso tom="erro" mensagem={mensagemDe(erro)} testID="erro-formulario" /> : null}

        <Botao
          titulo={rotuloEnvio}
          carregando={enviando}
          onPress={handleSubmit(aoEnviar)}
          testID={testID ?? 'botao-enviar-missao'}
        />
        <Text style={estilos.ajuda}>{nota}</Text>
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
                cor: coresMarcador[valores.categoria],
                // Mesmo segundo canal do radar: sem o glifo, o pino da origem era a única marca do
                // app que dependia só de matiz.
                glifo: glifoCategoria[valores.categoria],
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
            titulo={`${ponto.apelido} · ${rotuloTipoPonto(ponto.tipo)}`}
            variante="secundario"
            onPress={() => {
              setValue('pontoCustodiaId', ponto.id, { shouldValidate: true });
              setPontosAberto(false);
            }}
            testID={`ponto-${ponto.codigo}`}
          />
        ))}
      </FolhaInferior>
    </>
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
 *
 * `recompensaEmToken` não é exceção a isso: ele diz SE a missão paga em moeda, nunca QUANTO.
 */
export function paraRequest(dados: CriarMissaoForm): CriarMissaoRequest {
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
    recompensaEmToken: dados.recompensaEmToken,
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
  conteudo: { padding: espaco.lg, gap: espaco.md },
  rotulo: { ...tipografia.rotulo, color: cores.tinta70 },
  ajuda: { ...tipografia.legenda, color: textoAcessivel.suave },
  erro: { ...tipografia.legenda, color: textoAcessivel.coral },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: espaco.sm },
  /**
   * DESTAQUE POR BORDA, e não por preenchimento — e a troca é de contraste, não de gosto.
   *
   * O fundo era `cores.verdeClaro`, uma superfície que a auditoria de contraste da F12 nunca
   * cobriu: ela mediu os pares sobre `papel`, `branco` e os quatro fundos de chip. Sobre
   * `verdeClaro` (L≈0,865, mais escuro que o `ambarClaro` que o javadoc de `textoAcessivel` assume
   * como pior caso) os dois textos deste card reprovavam em WCAG AA — `suave` dava ≈4,16:1 e
   * `ambar` ≈4,39:1, contra os 4,5:1 que ambos precisam. O `xp` é `subtitulo`, 17 px, abaixo do
   * limiar de texto grande, então não há desconto a aplicar.
   *
   * Voltando ao branco do `Card`, os dois tokens ficam sobre o fundo em que FORAM auditados —
   * `suave` 4,78:1 e `ambar` 5,04:1 —, e o card continua destacado. É o mesmo recurso que as
   * conquistas já usam no perfil: borda verde em vez de fundo tingido, sem token novo.
   */
  cardRecompensa: { borderColor: cores.verdePrimario, gap: espaco.xs },
  recompensa: { flexDirection: 'row', alignItems: 'center', gap: espaco.lg },
  xp: { ...tipografia.subtitulo, color: textoAcessivel.ambar },
  mapaSeletor: { height: ALTURA_SELETOR_MAPA },
});
