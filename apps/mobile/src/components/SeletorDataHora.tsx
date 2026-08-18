import DateTimePicker, {
  type DateTimePickerChangeEvent,
} from '@react-native-community/datetimepicker';
import { useState } from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';

import { cores, espaco, raio, textoAcessivel, tipografia } from '@/theme';

interface Props {
  rotulo: string;
  valor: Date;
  aoMudar: (valor: Date) => void;
  minimo?: Date;
  erro?: string | null;
  testID?: string;
}

/**
 * Data e hora da janela da missão.
 *
 * **Dois passos, e não um.** O picker nativo do Android só faz data OU hora por vez — não existe
 * modo combinado. Em vez de bifurcar a tela por plataforma, o componente encadeia: escolhe a data,
 * abre a hora, e só então chama `aoMudar` uma única vez com o instante completo. No iOS o modo
 * `datetime` faz tudo de uma vez e o segundo passo é pulado.
 *
 * Um `aoMudar` por passo faria o formulário validar um estado intermediário — janela com a data
 * nova e a hora velha — e piscar erro de "fim antes do início" no meio da escolha.
 *
 * **Escolha e desistência entram por callbacks SEPARADOS** (`onValueChange` e `onDismiss`), e não
 * pelo `onChange` único, que a versão 9 da biblioteca depreciou. A diferença não é cosmética: com
 * `onChange` o cancelamento chegava como "mudou, mas talvez sem data", e distinguir os dois casos
 * dependia de inspecionar o argumento — no Android vinha sem data, no iOS vinha com a data antiga.
 * Agora cada desfecho tem seu próprio caminho, e `escolhido` deixa de ser opcional.
 *
 * `onNeutralButtonPress` fica de fora de propósito: é o botão "limpar" do Android, que só existe
 * quando configurado — e uma janela de missão não tem estado "sem valor" para voltar.
 */
export function SeletorDataHora({ rotulo, valor, aoMudar, minimo, erro, testID }: Props) {
  const [etapa, setEtapa] = useState<'fechado' | 'data' | 'hora'>('fechado');
  const [parcial, setParcial] = useState<Date>(valor);

  function abrir() {
    setParcial(valor);
    setEtapa(Platform.OS === 'ios' ? 'hora' : 'data');
  }

  /** Desistiu: fecha e não propaga nada. O valor do formulário continua o que já era. */
  function aoDispensar() {
    setEtapa('fechado');
  }

  function aoEscolher(_evento: DateTimePickerChangeEvent, escolhido: Date) {
    if (etapa === 'data') {
      // Guarda a data e mantém a hora que já estava: o passo seguinte só ajusta hora e minuto.
      const combinado = new Date(valor);
      combinado.setFullYear(escolhido.getFullYear(), escolhido.getMonth(), escolhido.getDate());
      setParcial(combinado);
      setEtapa('hora');
      return;
    }

    const finalizado = new Date(parcial);
    finalizado.setHours(escolhido.getHours(), escolhido.getMinutes(), 0, 0);
    setEtapa('fechado');
    aoMudar(finalizado);
  }

  return (
    <View style={estilos.campo}>
      <Text style={estilos.rotulo}>{rotulo}</Text>
      <Pressable
        onPress={abrir}
        accessibilityRole="button"
        accessibilityLabel={`${rotulo}: ${formatar(valor)}. Toque para alterar.`}
        style={[estilos.caixa, erro ? estilos.caixaComErro : null]}
        testID={testID}
      >
        <Text style={estilos.valor}>{formatar(valor)}</Text>
      </Pressable>
      {erro ? (
        <Text style={estilos.erro} accessibilityLiveRegion="polite">
          {erro}
        </Text>
      ) : null}

      {etapa !== 'fechado' ? (
        <DateTimePicker
          value={parcial}
          minimumDate={minimo}
          mode={etapa === 'data' ? 'date' : Platform.OS === 'ios' ? 'datetime' : 'time'}
          display="default"
          onValueChange={aoEscolher}
          onDismiss={aoDispensar}
          testID={testID ? `${testID}-picker` : undefined}
        />
      ) : null}
    </View>
  );
}

/** pt-BR explícito: o locale do aparelho mudaria o formato entre dispositivos e entre testes. */
function formatar(data: Date): string {
  return data.toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

const estilos = StyleSheet.create({
  campo: { gap: espaco.xs },
  rotulo: { ...tipografia.rotulo, color: cores.tinta70 },
  caixa: {
    minHeight: 48,
    justifyContent: 'center',
    paddingHorizontal: espaco.md,
    borderWidth: 1,
    borderColor: cores.linha,
    borderRadius: raio.md,
    backgroundColor: cores.branco,
  },
  caixaComErro: { borderColor: cores.coral },
  valor: { ...tipografia.corpo, color: cores.tinta },
  erro: { ...tipografia.legenda, color: textoAcessivel.coral },
});
