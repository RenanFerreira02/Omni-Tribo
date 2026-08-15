package com.omnitribo.logistica.treino;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Escreve o dataset em CSV. Roda SÓ sob demanda — ver {@code tools/dataset/gerar.sh}.
 *
 * <p>Guardado por propriedade de sistema porque escrever arquivo no CI é efeito colateral sem
 * propósito: ninguém lê o CSV de uma execução de pipeline, e um teste que escreve em disco a cada
 * build acaba escrevendo em lugar errado mais cedo ou mais tarde. O que o CI precisa provar sobre o
 * dataset — que ele é reprodutível bit a bit — está em {@code DatasetSinteticoTest}, por digest,
 * sem tocar o sistema de arquivos.
 */
@EnabledIfSystemProperty(named = "exportarDataset", matches = "true")
class ExportadorDatasetCsvTest {

  @Test
  void exportar() throws IOException {
    Path destino =
        Path.of(System.getProperty("caminhoCsv", "target/dataset/entregas-sinteticas.csv"));
    Files.createDirectories(destino.getParent());

    ArtefatosDoModelo artefatos = ArtefatosDoModelo.treinar();
    Files.writeString(destino, artefatos.csv(), StandardCharsets.UTF_8);

    System.out.println("CSV escrito em " + destino.toAbsolutePath());
  }
}
