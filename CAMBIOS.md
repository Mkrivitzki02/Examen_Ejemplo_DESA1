# Cambios realizados

## Funcionalidad 1: umbral de alarma

**Validación.** `PlantRules.validateThreshold()` (`model/Reading.kt`) convierte el texto (acepta `72.5` o `72,5`) y devuelve `Valid(valor)` o `Invalid(mensaje)`. Rechaza lo que no es número (incluidos `NaN`/`Infinity`) y los valores fuera de **30–120 °C**. El mensaje se muestra debajo del campo, en el diálogo Compose `ThresholdDialog`, y el valor inválido no se guarda.

**Persistencia.** Se usa la tabla `configuration` que ya existía en SQLite (clave `temperature_threshold`). `PlantDatabase.saveThreshold()` hace un *upsert* (`CONFLICT_REPLACE`) y `readThreshold()` devuelve **65 °C** si no hay nada guardado o si el valor guardado es inválido. El ViewModel lo carga en su `init`, así que sobrevive a cerrar y volver a abrir la app.

**Actualización inmediata.** `DashboardState.alertCount` pasó a ser una propiedad **derivada** de `readings` y `threshold`, y cada tarjeta calcula NORMAL/ALERTA con `PlantRules.requiresAttention(reading, state.threshold)`. Al guardar, solo se cambia `threshold` en el estado y Compose redibuja el contador y las etiquetas en ese momento, sin sincronizar. La regla es `temperatura >= umbral`.

## Funcionalidad 2: historial

**Consulta.** `PlantDatabase.history(machineId)` ejecuta un `SELECT ... WHERE machine_id = ? ORDER BY captured_at DESC, id DESC LIMIT 10` con parámetros. El desempate por `id` mantiene el orden cuando dos lecturas tienen la misma hora. La expone `PlantRepository.history()`, que corre en `Dispatchers.IO`.

**Presentación.** `PlantViewModel.showHistory()` abre un `HistoryState` y carga los datos. `HistoryDialog` (Compose) muestra para cada lectura la **fecha/hora** (con segundos), la **temperatura** (en rojo si supera el umbral) y el **origen**. Contempla tres estados: cargando, error y vacío ("Este equipo todavía no tiene lecturas guardadas…").

**Cerrar sin perder la búsqueda.** El historial es un diálogo encima del panel. `closeHistory()` solo pone `history = null` y no toca `filter` ni `readings`. Si se sincroniza con el historial abierto, se recarga.

## Refactorizaciones

### 1. Inyección SQL en la búsqueda (seguridad)
- **Problema:** `findLatestByName` armaba el SQL concatenando el texto del usuario (`LIKE '%$filter%'`). Escribir una comilla `'` hacía fallar la consulta, y un texto como `' OR '1'='1` modificaba la consulta.
- **Corrección:** el valor viaja como parámetro `?` en `rawQuery(sql, arrayOf(patrón))`. Además se escapan `%`, `_` y `\` con `ESCAPE '\'`, para que se busquen como caracteres literales.
- **Beneficio:** se elimina un riesgo de seguridad y un cierre por `SQLiteException`. Está cubierto por `PlantDatabaseTest.searchIsSafeAgainstSqlInjectionAndWildcards`.

### 2. Umbral "65" duplicado en tres lugares (diseño / mantenibilidad)
- **Problema:** el límite estaba escrito como literal en `PlantRules`, en `PlantViewModel.setReadings` (contador) y en `EquipmentCard` (etiqueta). Cambiar uno sin los otros dejaba la pantalla incoherente, y hacía imposible un umbral configurable.
- **Corrección:** `PlantRules` es la única fuente de la regla (`requiresAttention(reading, threshold)`, `countAlerts`, límites y validación). La pantalla y el estado reciben el umbral vigente, y `alertCount` se deriva en lugar de guardarse aparte.
- **Beneficio:** el contador y las etiquetas no pueden contradecirse, y la regla se prueba sin Android (`PlantRulesTest`).

### 3. Acceso a SQLite en el hilo principal (rendimiento)
- **Problema:** `init` y `filter()` del ViewModel consultaban la base desde el hilo de la interfaz, en cada tecla del buscador. Con muchas lecturas eso traba la pantalla y puede provocar un ANR ("la app no responde").
- **Corrección:** todos los métodos del repositorio son `suspend` y corren en `Dispatchers.IO` con `withContext`. La búsqueda cancela la anterior (`searchJob`) para que una respuesta vieja no pise a la nueva. Las lecturas de cada sincronización se insertan en una transacción (`insertAll`).
- **Beneficio:** la interfaz no se bloquea y se escribe en disco una vez por lote en lugar de una vez por fila.

### 4. Fuga de la Activity y repositorio difícil de probar (diseño / testabilidad)
- **Problema:** `PlantRepository(this)` guardaba la Activity dentro de un objeto que vive tanto como el ViewModel. Al rotar la pantalla, la Activity vieja no se liberaba de la memoria. Además, el ViewModel dependía de una clase concreta con red y SQLite, así que no se podía probar.
- **Corrección:** el repositorio usa `context.applicationContext`. Se extrajo la interfaz `PlantDataSource`, que es la que recibe el ViewModel. `sync()` devuelve el mensaje de estado en lugar de exponer una variable mutable (`lastSyncMessage`).
- **Beneficio:** no hay fuga de memoria, y `PlantViewModelTest` prueba la lógica de pantalla con un repositorio falso en memoria.

### 5. Migración destructiva de la base (datos)
- **Problema:** `onUpgrade` borraba las tablas. Cualquier cambio de versión eliminaba el historial y el umbral guardado.
- **Corrección:** migración incremental a la versión 2, que solo agrega el índice `idx_readings_machine_time (machine_id, captured_at)`. Ese índice acelera la consulta del historial y el `GROUP BY machine_id`.
- **Beneficio:** al actualizar la app no se pierden datos del usuario, y las consultas siguen rápidas aunque crezcan las lecturas.

Se conservan Retrofit, SQLite (`SQLiteOpenHelper`), los sensores del teléfono y la convivencia XML (encabezado) + Compose (panel).

## Evidencia de validación

### Pruebas automatizadas

```bash
./gradlew testDebugUnitTest            # 12 pruebas JVM, sin emulador
./gradlew connectedDebugAndroidTest    # 5 pruebas SQLite reales, requiere emulador
```

| Archivo | Qué comprueba | Resultado |
|---|---|---|
| `test/.../PlantRulesTest.kt` (7) | `>=` inclusivo, conteo con umbral variable, límites 30 y 120 aceptados, 29.9 / 120.1 / texto / NaN rechazados, coma decimal | 7/7 OK |
| `test/.../PlantViewModelTest.kt` (5) | umbral persistido se carga al iniciar; guardar 55 sube las alertas de 1 a 2 **sin llamar a sync**; 150 muestra error y no guarda; tras **dos sincronizaciones** el historial muestra las nuevas lecturas, de la más reciente a la más vieja; cerrar el historial conserva el filtro; historial vacío | 5/5 OK |
| `androidTest/.../PlantDatabaseTest.kt` (5) | 65 °C en instalación nueva; el umbral sobrevive a cerrar y reabrir la base; con 15 lecturas devuelve exactamente las 10 más recientes y solo de ese equipo; equipo sin lecturas → lista vacía; búsqueda con `'`, `' OR '1'='1` y `%` no rompe ni devuelve todo | 5/5 OK (emulador API 35) |

### Prueba manual (emulador Pixel API 35)

1. Instalación limpia → umbral **65 °C**, **Alertas: 1** (Horno C en ALERTA). ✔
2. Umbral → escribir `150` → Guardar → "El umbral debe estar entre 30 y 120 °C."; el diálogo sigue abierto. ✔
3. Escribir `abc` → "Ingresá un número, por ejemplo 70." ✔
4. Escribir `55` → Guardar → botón "Umbral: 55 °C", Compresor A (57.2 °C) pasa a ALERTA, **Alertas: 2**, sin sincronizar. ✔
5. Forzar cierre de la app y reabrir → sigue en **55 °C** y **Alertas: 2**. ✔
6. Desactivar wifi y datos → Sincronizar dos veces → "Sin conexión a API: usando datos simulados", sin cierres. ✔
7. Buscar `horno` → Ver historial → 3 lecturas (inicial + 2 sincronizaciones) con fecha/hora, temperatura y origen, de la más reciente a la más vieja. ✔
8. Cerrar → el buscador sigue con `horno` y la lista filtrada. ✔
9. Agregar `'` a la búsqueda → "No hay equipos para mostrar." (antes: excepción SQL). ✔
10. Reactivar la red → Sincronizar → "Sincronizado con ThingSpeak (canal público de demostración)". ✔

`logcat -b crash` sin entradas de la app durante toda la prueba.

### Nota de entorno
El repositorio original no incluía `gradle/wrapper/gradle-wrapper.jar`, por lo que `./gradlew` fallaba desde la terminal. Se agregó el wrapper de Gradle 8.11.1, la versión declarada en `gradle-wrapper.properties`. Hay que compilar con **JDK 17 o 21**: Gradle 8.11 no funciona con JDK 25.
