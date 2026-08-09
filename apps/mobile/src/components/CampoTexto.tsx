import { useState } from 'react';
import { StyleSheet, Text, TextInput, View, type TextInputProps } from 'react-native';

import { cores, espaco, raio, textoAcessivel, tipografia } from '@/theme';

interface Props extends TextInputProps {
  rotulo: string;
  /** Mensagem de erro do campo. Pode vir da validação local (Zod) ou de `errors[]` do backend. */
  erro?: string | null;
}

export function CampoTexto({ rotulo, erro, ...resto }: Props) {
  const [focado, setFocado] = useState(false);
  const temErro = Boolean(erro);

  return (
    <View style={estilos.grupo}>
      <Text style={estilos.rotulo}>{rotulo}</Text>
      <TextInput
        // O rótulo visual não chega ao leitor de tela sozinho: TextInput não é associado a <Text>
        // como um <label for> da web. Sem isto o campo é anunciado só como "caixa de edição".
        accessibilityLabel={rotulo}
        accessibilityHint={erro ?? undefined}
        placeholderTextColor={textoAcessivel.suave}
        onFocus={() => setFocado(true)}
        onBlur={() => setFocado(false)}
        style={[
          estilos.campo,
          focado && estilos.focado,
          temErro && estilos.comErro,
          resto.multiline && estilos.multilinha,
        ]}
        {...resto}
      />
      {temErro ? <Text style={estilos.erro}>{erro}</Text> : null}
    </View>
  );
}

const estilos = StyleSheet.create({
  grupo: { gap: espaco.xs },
  rotulo: { ...tipografia.rotulo, color: cores.tinta70 },
  campo: {
    minHeight: 48,
    borderWidth: 1,
    borderColor: textoAcessivel.borda,
    borderRadius: raio.md,
    paddingHorizontal: espaco.md,
    backgroundColor: cores.branco,
    color: cores.tinta,
    ...tipografia.corpo,
  },
  multilinha: { minHeight: 96, paddingTop: espaco.md, textAlignVertical: 'top' },
  focado: { borderColor: cores.verdePrimario },
  comErro: { borderColor: cores.coral },
  erro: { ...tipografia.legenda, color: textoAcessivel.coral },
});
