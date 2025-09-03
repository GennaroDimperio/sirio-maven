# Adaptive Pool Control – Fase 4

Simulazione **Sirio/GSPN** con adattamento runtime del numero di repliche (`Pool`) su **timeseries di arrivi**.  
Obiettivo: scegliere il **minimo** `Pool+busy` che rispetta **SLO** (tasso di rejection ≤ `0.01`) nel prossimo orizzonte, usando **stima a finestra mobile** e **probability switch** sui pesi `W**`.

## Prerequisiti
- Java **JDK 24**
- Maven
- Libreria **Sirio 2.0.5** dichiarata nel `pom.xml`

## Struttura (minimo)
`src/main/java/com/example/`
- **ModelOris2_fase4.java** – modello GSPN (fase 4). 
  - Include helper per settare e leggere i parametri (`Pool`, `Rate*`, pesi `W**`, ecc.).
- **SlidingRateEstimator.java** – Stima dei **rate a finestra mobile**, quando non si conoscono più i parametri di generazione.  
  Parametri:  
  - `WINDOW_W` = ampiezza finestra (es. 20s)  
  - `STEP_K` = passo di aggiornamento (es. 5s, deve essere < W)
- **TimeseriesSimulator.java** – Simulatore con **controllore mobile**:  
  - legge `arrivals.csv`  
  - disattiva il processo di arrivo automatico  
  - inietta gli arrivi manualmente (probability switch sui pesi W**)  
  - simula la rete tra un arrivo e il successivo  
  - raccoglie metriche di interesse: **rejection totali**, **idle medio (Pool)**  
  - salva i risultati in `timeseries_sli.csv`.


Opzionali:
- **ArrivalGenerator.java** – Genera lo **scenario di arrivi** (workload con “risacca”) e produce `arrivals.csv`.  
  Parametri principali (durata, seed, intensità) definiti in cima al file.

## Compilazione
```bash
mvn -q -DskipTests clean package
```

## Dati di input
- **`arrivals.csv`** (obbligatorio):  
  CSV con header `t,cls` e righe tipo:
  ```
  0.449,1
  1.732,3
  ...
  ```

## Esecuzione (simulatore con controllore)
```bash
mvn -q -DskipTests exec:java -Dexec.mainClass=com.example.TimeseriesSimulator
```

Output tipico:
```
[progress] 300/316  t=288.267  rej=2  Pool=0  busy=10

== RISULTATI TIMESERIES (baseline) ==
Tempo totale simulato: 299.144 s
Rejection totali:      2
Rejection rate:        0.006329
Idle medio (Pool):     4.435
CSV scritto: timeseries_sli.csv
CSV per intervalli: timeseries_intervals.csv
```

### File prodotti
- `timeseries_sli.csv`
  ```
  total_time_s,rejections,rejection_rate,idle_mean
  299.144,2,0.006329,4.435
  ```
- `timeseries_intervals.csv`
  (metriche per intervallo: `t_start,t_end,pool_now,target_tot,...`)

## (Opzionale) Generare la timeseries
```bash
mvn -q -DskipTests exec:java -Dexec.mainClass=com.example.ArrivalGenerator
```
Produce `arrivals.csv` con carico “a risacca”.

## Parametri chiave (TimeseriesSimulator)
- **Finestra/controllo**: `W_WINDOW_SEC=20`, `ADAPT_STEP_SEC=3`
- **Orizzonte predittivo**: `PRED_HORIZ_SEC=20`
- **SLO**: `0.01`
- **Limiti**: `POOL_MIN=1`, `POOL_MAX=24`

## Come valutare
- **Qualità**: `rejection_rate` complessivo ≤ **0.01**
- **Efficienza**: `idle_mean` (più basso = meglio), più breakdown per intervallo in `timeseries_intervals.csv`.

## Riproducibilità
- Seed fisso nei generatori/simulatori.
