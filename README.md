# Adaptive Pool Control – Fase 4 (patched)

Simulazione **Sirio/GSPN** con adattamento runtime del numero di repliche (`Pool`) su **timeseries di arrivi**.  
L’obiettivo è scegliere il **minimo** `Pool + busy` che rispetta lo **SLO** (tasso di rejection <= `0.01`) nell’orizzonte successivo, usando **stima a finestra mobile** e **probability switch** sui pesi `W**`.

## Patch dell'11/09
- **Tre modalità di controllo** con confronto diretto:
  1. **Previsione default** (orizzonte e periodo di controllo predefiniti).
  2. **Previsione custom** (orizzonte e periodo di controllo scelti a runtime).
  3. **Senza previsione** (decisione basata solo sullo stato corrente).
- **Variazione del periodo di controllo** nella modalità custom, per valutare l’effetto di “ogni quanti secondi” si guarda il futuro.
- **Debug delle prime 20 richieste**:
  - Log di **ogni movimento** interno.
  - All’arrivo: **stato completo** (Pool e token in ogni fase).
  - CSV separato: `timeseries_debug.csv` con formato `time|event`.
- **File output taggati per modalità** per confronti rapidi:
  - `timeseries_sli_<mode>.csv`
  - `timeseries_intervals_<mode>.csv`
  - `<mode> = {default, custom_h<H>_p<P>, nofuture}`

---

## Prerequisiti
- Java **JDK 24**
- Maven
- Libreria **Sirio 2.0.5** dichiarata nel `pom.xml`

## Struttura repo
`src/main/java/com/example/`
- **ModelOris2_fase4.java** – Modello GSPN (fase 4) e helper per token/parametri (`Pool`, `Rate*`, pesi `W**`, ecc.)
- **SlidingRateEstimator.java** – Stima dei **rate** con finestra scorrevole  
  Parametri:  
  - `windowSec` = ampiezza finestra (es. 20s)  
  - `stepSec`   = passo aggiornamento (di default uguale al periodo di controllo)
- **TimeseriesSimulator.java** – Simulatore con **controllore adattivo**:
  - legge `arrivals.csv`
  - disattiva arrivi automatici
  - **inietta** gli arrivi (probability switch sui pesi `W**`)
  - simula la rete tra eventi con “**gara di esponenziali**”
  - tre **modalità** (default/custom/nofuture) con **file output taggati**
  - **debug** delle prime 20 richieste in `timeseries_debug.csv`
- (Opzionale) **ArrivalGenerator.java** – Genera uno scenario di arrivi (`arrivals.csv`)


## Dati di input
- **`arrivals.csv`** (obbligatorio):  
  CSV con header `t,cls`, es.:
  ```
  t,cls
  0.449,1
  1.732,3
  ...
  ```

All’avvio, il programma chiede la **modalità** di esecuzione del controller:
```
1 = previsione default
2 = previsione con parametri custom
3 = senza previsione (solo stato corrente)
```
- Se scegli **2**, verranno richiesti:
  - **Orizzonte di previsione (sec)** → numero reale positivo
  - **Periodo di controllo (sec)**    → numero reale positivo  
  (L’input viene validato; in caso di dato non valido viene richiesto di reinserirlo.)

### File prodotti
- `timeseries_sli_<mode>.csv` – **riassunto complessivo**  
  Colonne: `total_time_s,rejections,rejection_rate,idle_mean`
- `timeseries_intervals_<mode>.csv` – **metriche per intervallo**  
  Colonne: `t_start,t_end,pool_now,target_tot,eff_change_time,arrivals,rejections,rejection_rate,idle_mean_interval,pred_rej_at_target,pred_n`
- `timeseries_debug.csv` – **debug** (prime 20 richieste):  

  Esempi:
  ```
  0.449|arrivo classe=3: accettato in Ph3; stato corrente: Pool=7, Ph1=0, Ph2=0, Ph3=1, Ph4=0
  1.069|movimento: token spostato da Ph3 a Ph4
  2.576|movimento: token completato da Ph4 a Pool
  ...
  14.819|== STOP DEBUG: raggiunte 20 richieste ==
  ```

## Parametri chiave (di default)
- **Sliding window**: `FINESTRA_STIMA_SEC = 20`
- **Periodo di controllo**: `PERIODO_CONTROLLO_SEC = 10`  
  (in modalità custom può essere impostato a runtime)
- **Orizzonte predittivo**: `ORIZZONTE_PREVISIONE_SEC = 10`  
  (in modalità custom può essere impostato a runtime)
- **SLO**: `SLO_REJECTION = 0.01`
- **Limiti risorse**: `POOL_MIN = 1`, `POOL_MAX = 24`
- **Seed** fisso per riproducibilità

## Esempio di output a fine run
```
== RISULTATI TIMESERIES ==
Tempo totale simulato: 299.144 s
Rejection totali:      2
Rejection rate:        0.006329
Idle medio (Pool):     4.435
CSV scritto: timeseries_sli_default.csv
CSV per intervalli: timeseries_intervals_default.csv
CSV debug: timeseries_debug.csv
```
