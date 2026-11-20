#!/usr/bin/env python3
"""Genera web/js/export/dxf-template.js: plantilla DXF R2000 (AC1015) creada con ezdxf.

AutoCAD exige en DXF R2000 tablas, bloques y objetos que no conviene escribir a mano. Esta plantilla
se crea una sola vez con ezdxf (la biblioteca de referencia para DXF en Python) con las capas del
levantamiento, unidades en metros y sin entidades. El escritor de la app (web/js/export/dxf.js)
solo agrega las entidades (puntos, líneas, textos, polilíneas) con identificadores nuevos.

Uso:  pip install ezdxf==1.4.5 && python3 tools/make_dxf_template.py
"""
import io
import json
import pathlib

import ezdxf

LAYERS = [
    # nombre, color ACI, descripción
    ("ESTACION", 1, "Estacion del teodolito"),
    ("PUNTOS", 7, "Puntos levantados"),
    ("CODIGOS", 7, "Codigos de los puntos"),
    ("COTAS", 8, "Cota de cada punto"),
    ("RADIACIONES", 4, "Visuales desde la estacion"),
    ("LINEAS", 2, "Lineas definidas por el usuario"),
    ("TRIANGULACION", 9, "Malla de triangulos (Delaunay)"),
    ("CURVAS_NIVEL", 3, "Curvas de nivel"),
    ("CURVAS_TEXTO", 3, "Rotulos de las curvas de nivel"),
    ("NOTAS", 7, "Notas, norte y unidades"),
]


def main():
    doc = ezdxf.new("R2000", setup=False)
    doc.units = ezdxf.units.M  # $INSUNITS = 6 (metros)
    doc.header["$MEASUREMENT"] = 1  # métrico
    doc.header["$LUNITS"] = 2  # decimal
    doc.header["$LUPREC"] = 3  # 3 decimales (milímetro)
    doc.header["$AUNITS"] = 0  # grados decimales
    doc.header["$PDMODE"] = 3  # puntos visibles como "X"
    doc.header["$PDSIZE"] = 0.0
    for name, color, desc in LAYERS:
        layer = doc.layers.add(name, color=color)
        layer.description = desc
    stream = io.StringIO()
    doc.write(stream)
    text = stream.getvalue().replace("\r\n", "\n")
    lines = text.split("\n")

    out = []
    i = 0
    first_handle = None
    ms_handle = doc.block_records.get("*Model_Space").dxf.handle
    while i < len(lines) - 1:
        code, value = lines[i], lines[i + 1]
        out.extend([code, value])
        if code.strip() == "9" and value in ("$HANDSEED",):
            first_handle = lines[i + 3].strip()
            out.extend([lines[i + 2], "%%HANDSEED%%"])
            i += 4
            continue
        if code.strip() == "9" and value in ("$EXTMIN", "$EXTMAX"):
            tag = "MIN" if value == "$EXTMIN" else "MAX"
            out.extend([lines[i + 2], f"%%EXT{tag}_X%%", lines[i + 4], f"%%EXT{tag}_Y%%", lines[i + 6], f"%%EXT{tag}_Z%%"])
            i += 8
            continue
        if code.strip() == "2" and value == "ENTITIES" and out[-4:-2] == ["  0", "SECTION"]:
            out.append("%%ENTITIES%%")
        i += 2
    template = "\n".join(out).replace("ENTITIES\n%%ENTITIES%%\n  0\nENDSEC", "ENTITIES\n%%ENTITIES%%  0\nENDSEC")
    assert "%%HANDSEED%%" in template and "%%ENTITIES%%" in template and "%%EXTMAX_Y%%" in template

    target = pathlib.Path(__file__).resolve().parent.parent / "web" / "js" / "export" / "dxf-template.js"
    target.write_text(
        "// GENERADO por tools/make_dxf_template.py con ezdxf " + ezdxf.__version__ + " — no editar a mano.\n"
        "// Plantilla DXF R2000 (AC1015), unidades en metros ($INSUNITS = 6), sin entidades.\n"
        f"export const MODEL_SPACE_HANDLE = {json.dumps(ms_handle)};\n"
        f"export const FIRST_FREE_HANDLE = {json.dumps(first_handle)};\n"
        f"export const LAYERS = {json.dumps([n for n, _, _ in LAYERS])};\n"
        f"export const TEMPLATE = {json.dumps(template, ensure_ascii=False)};\n",
        encoding="utf-8",
    )
    print("Plantilla escrita en", target, "— handle del espacio modelo:", ms_handle)


if __name__ == "__main__":
    main()
