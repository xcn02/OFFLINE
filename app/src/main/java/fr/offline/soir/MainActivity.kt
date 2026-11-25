package fr.offline.soir

import android.Manifest
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import android.widget.Toast
import android.media.ToneGenerator
import android.media.AudioManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Scheduler.schedule(this)
        Scheduler.startOverlayIfDue(this)
        setContent { OfflineApp(intent.getBooleanExtra("start_routine", false)) }
    }
}

private val fmt = DateTimeFormatter.ofPattern("HH:mm")
private fun nowText() = LocalTime.now().format(fmt)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineApp(startImmediately: Boolean = false) {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var cfg by remember { mutableStateOf(store.load()) }
    var nights by remember { mutableStateOf(store.nights()) }
    var page by remember { mutableStateOf(0) }
    var editor by remember { mutableStateOf<Task?>(null) }
    var adding by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var expandedHistory by remember { mutableStateOf("") }
    var editingHistory by remember { mutableStateOf("") }
    var doneVersion by remember { mutableIntStateOf(0) }
    val today = eveningDate().toString()
    var tomorrow by remember(today) { mutableStateOf(store.getText("tomorrow_$today")) }
    var journal by remember(today) { mutableStateOf(store.getText("journal_$today")) }
    fun updateNote(type: String, value: String, date: String = today) {
        store.setText("${type}_$date", value)
        store.ensureNight(date)
        if (date == today) { if (type == "tomorrow") tomorrow = value else journal = value }
        nights = store.nights()
    }
    fun done(taskId: Long): Boolean { @Suppress("UNUSED_VARIABLE") val v=doneVersion; return store.getBoolean("done_${today}_$taskId") }
    fun setDone(taskId: Long, checked: Boolean) { store.setBoolean("done_${today}_$taskId", checked);doneVersion++ }
    var cut by remember { mutableStateOf(false) }
    var slept by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf(store.session()?.takeIf { it.date == today }) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmPlane by remember { mutableStateOf(false) }
    var grayInfo by remember { mutableStateOf(false) }
    val activeTasks = cfg.tasks.filter { it.enabled }
    val activeIndex = activeTasks.indexOfFirst { it.id == session?.taskId }
    val activeTask = activeTasks.getOrNull(activeIndex)
    val elapsedMs = if (session == null) 0L else ((if (session!!.pausedMs > 0) session!!.pausedMs else clock) - session!!.startedMs).coerceAtLeast(0L)
    fun setSession(new: Store.Session?) { session = new; store.saveSession(new) }
    fun startRoutine() {
        if (!store.isStarted(today)) store.updateNight(today) { it.copy(cutAt = nowText()) }
        cut = true; slept = false; nights = store.nights()
        Scheduler.stopRepeat(ctx)
        Scheduler.stopOverlay(ctx)
        val first = cfg.tasks.firstOrNull { it.enabled && !done(it.id) }
        if (first != null && (session == null || session?.date != today)) {
            session = Store.Session(today, first.id, System.currentTimeMillis())
            store.saveSession(session)
        }
        page = 1
    }
    LaunchedEffect(startImmediately) { if (startImmediately) startRoutine() }
    fun finishNight() {
        store.updateNight { it.copy(sleptAt=nowText()) }
        slept = true
        nights = store.nights()
        setSession(null)
        Scheduler.stopRepeat(ctx)
        Scheduler.stopOverlay(ctx)
        page = 0
        if (cfg.plane) {
            if (cfg.planeConfirm) confirmPlane = true else {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
                    .onFailure { Toast.makeText(ctx, "Réglage mode avion indisponible", Toast.LENGTH_LONG).show() }
            }
        }
    }
    fun advance() {
        val current = session ?: return
        val position = activeTasks.indexOfFirst { it.id == current.taskId }
        if (position < 0) { setSession(null); return }
        setDone(current.taskId, true)
        if (cfg.stepSound) runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 25).apply { startTone(ToneGenerator.TONE_PROP_BEEP, 100); release() }
        }
        val next = activeTasks.drop(position + 1).firstOrNull { !done(it.id) }
        if (next != null) setSession(Store.Session(current.date, next.id, System.currentTimeMillis()))
        else setSession(current.copy(completed=true, pausedMs=System.currentTimeMillis()))
    }
    LaunchedEffect(session?.taskId, session?.startedMs, session?.pausedMs, session?.completed, cfg.transition, cfg.tasks) {
        while (true) {
            delay(500)
            clock = System.currentTimeMillis()
            val state = session ?: continue
            val task = cfg.tasks.firstOrNull { it.id == state.taskId } ?: continue
            if (state.completed || state.pausedMs > 0) continue
            val isTextEntry = task.id == 3L || task.id == 4L
            val autoAdvance = cfg.transition == "auto" || (cfg.transition == "hybrid" && !isTextEntry)
            if (autoAdvance && clock - state.startedMs >= task.minutes * 60_000L) advance()
        }
    }
    LaunchedEffect(page,doneVersion) {
        nights = store.nights()
        cut = store.isStarted(today)
        slept = store.isFinished(today)
    }
    fun save(c: Config) {
        cfg = c; store.save(c)
        val service = Intent(ctx, UnlockOverlayService::class.java)
        if (c.unlockPopup && c.popupOverlay && Settings.canDrawOverlays(ctx)) {
            Scheduler.startOverlayIfDue(ctx, c)
        } else ctx.stopService(service)
    }
    LaunchedEffect(cfg.unlockPopup, cfg.popupOverlay) {
        Scheduler.startOverlayIfDue(ctx, cfg)
    }
    val colors = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFFB2D6FF), surface = androidx.compose.ui.graphics.Color(0xFF111B2A), background = androidx.compose.ui.graphics.Color(0xFF0B1320))
    MaterialTheme(colorScheme = colors) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("OFFLINE", fontWeight = FontWeight.Bold) }, actions = {
                IconButton(onClick = { page = 3 }) { Icon(Icons.Default.Settings, "Paramètres") }
            })
        }, bottomBar = {
            NavigationBar {
                listOf(Triple("Accueil",Icons.Default.Home,0),Triple("Routine",Icons.Default.Checklist,1),Triple("Historique",Icons.Default.BarChart,2),Triple("Réglages",Icons.Default.Settings,3)).forEach { (name,icon,index) ->
                    NavigationBarItem(selected = page == index, onClick={page=index}, icon={Icon(icon,name)},label={Text(name)})
                }
            }
        }) { pad ->
            LazyColumn(modifier=Modifier.fillMaxSize().padding(pad), contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
                when (page) {
                    0 -> {
                        item { Heading("Ta fin de soirée", "Objectif : au lit à ${cfg.bed}") }
                        item {
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Text(if(slept) "Soirée terminée ✓" else if(cut) "Routine en cours" else "Prêt à décrocher ?",style=MaterialTheme.typography.headlineSmall)
                                    Text(if(slept) "Bonne nuit. OFFLINE ne te relancera plus pour cette soirée." else if(cut) "Passe à tes activités, puis termine avec « Je vais dormir »." else "Éteins le PC et commence la routine du soir.")
                                    if (!cut && !slept) {
                                        Button(onClick={startRoutine()},modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.PowerSettingsNew,null); Spacer(Modifier.width(8.dp)); Text("Je coupe") }
                                        OutlinedButton(onClick={
                                            store.setText("snooze_$today", (System.currentTimeMillis()+10*60_000L).toString())
                                            Scheduler.scheduleRepeat(ctx,10)
                                            Toast.makeText(ctx,"Dernière activité : rappel dans 10 minutes",Toast.LENGTH_SHORT).show()
                                        },modifier=Modifier.fillMaxWidth()) { Text("Je termine dans 10 minutes") }
                                    }
                                    if (cut && !slept) Button(onClick={
                                        finishNight()
                                    },modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Bedtime,null); Spacer(Modifier.width(8.dp)); Text("Je vais dormir") }
                                }
                            }
                        }
                        item {
                            val yesterday = LocalDate.now().minusDays(1).toString()
                            val priorities = store.getText("tomorrow_$yesterday")
                            if (priorities.isNotBlank()) SettingCard("Priorités notées hier") { Text(priorities) }
                        }
                        item { NotesPanel(tomorrow, journal, onTomorrow={updateNote("tomorrow", it)}, onJournal={updateNote("journal", it)}, initiallyExpanded=false) }
                        if (cfg.morningRoutine && LocalTime.now().isBefore(LocalTime.NOON) &&
                            (cfg.morningStep1.isNotBlank() || cfg.morningStep2.isNotBlank())) item {
                            val day = LocalDate.now().toString()
                            SettingCard("Au lever · aujourd'hui") {
                                listOf(cfg.morningStep1, cfg.morningStep2).forEachIndexed { index, instruction ->
                                    if (instruction.isNotBlank()) {
                                        val key = "morning_done_${day}_$index"
                                        var checked by remember(day, index) { mutableStateOf(store.getBoolean(key)) }
                                        Row(verticalAlignment=Alignment.Top) {
                                            Checkbox(checked=checked,onCheckedChange={ checked=it;store.setBoolean(key,it) })
                                            Column(modifier=Modifier.weight(1f).padding(top=9.dp)) {
                                                Text(instruction,style=MaterialTheme.typography.bodyLarge)
                                                val detail = if (index == 0) cfg.morningDetail1 else cfg.morningDetail2
                                                if (detail.isNotBlank()) {
                                                    Text(detail,style=MaterialTheme.typography.bodySmall,
                                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item { Heading("Routine prévue", "${cfg.tasks.filter{it.enabled}.sumOf{it.minutes}} minutes d'activités") }
                        items(cfg.tasks.filter{it.enabled}) { t -> ListItem(headlineContent={Text(t.name)}, supportingContent={Text("${t.minutes} min${if(t.optional) " · facultatif" else ""}")}, leadingContent={Icon(Icons.Default.CheckCircleOutline,null)}) }
                        item { Text("Les réglages système protégés (niveaux de gris, mode avion) restent manuels sur Android standard.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    1 -> {
                        item { Heading("Routine du soir", "Progression discrète et activités personnalisables") }
                        if (cut && !slept && session == null && activeTasks.isNotEmpty()) item {
                            Button(onClick={setSession(Store.Session(today,activeTasks.firstOrNull{!done(it.id)}?.id ?: activeTasks.first().id,System.currentTimeMillis()))}, modifier=Modifier.fillMaxWidth()) { Text("Démarrer la routine") }
                        }
                        if (cut && !slept && activeTask != null && session?.completed == false) item {
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Text("ACTIVITÉ ${activeIndex+1} / ${activeTasks.size}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                                    Text(activeTask.name,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                                    val fraction = (elapsedMs.toFloat() / (activeTask.minutes*60_000f)).coerceIn(0f,1f)
                                    LinearProgressIndicator(progress={fraction}, modifier=Modifier.fillMaxWidth().height(6.dp))
                                    Text("${activeTask.minutes} min prévues · ${ (fraction*100).toInt() } %",style=MaterialTheme.typography.bodySmall)
                                    if (activeTask.id == 4L) {
                                        OutlinedTextField(value=journal,onValueChange={updateNote("journal", it)},label={Text("Journal rapide")},modifier=Modifier.fillMaxWidth(),minLines=3)
                                    }
                                    if (activeTask.id == 3L) {
                                        OutlinedTextField(value=tomorrow,onValueChange={updateNote("tomorrow", it)},label={Text("Priorités de demain")},modifier=Modifier.fillMaxWidth(),minLines=3)
                                    }
                                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick={
                                            val s=session ?: return@OutlinedButton
                                            setSession(if(s.pausedMs>0) s.copy(startedMs=System.currentTimeMillis()-(s.pausedMs-s.startedMs),pausedMs=0) else s.copy(pausedMs=System.currentTimeMillis()))
                                        },modifier=Modifier.weight(1f)) { Text(if(session?.pausedMs==0L) "Pause" else "Reprendre") }
                                        Button(onClick={advance()},modifier=Modifier.weight(1f)) { Text("Suivant") }
                                    }
                                    Text("En mode hybride, le journal et la préparation attendent ton appui sur Suivant.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (session?.completed == true && cut && !slept) item {
                            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                                Text("Toutes les activités sont terminées ✓",fontWeight=FontWeight.Bold)
                                Text("Tu peux maintenant poser ton téléphone.")
                            } }
                        }
                        item { Text("Tu peux terminer une étape plus tôt ou la mettre en pause. Les minutes sont indicatives, pas une obligation.",style=MaterialTheme.typography.bodySmall) }
                        items(cfg.tasks.filter { it.enabled }, key={"check_"+it.id}) { task ->
                            val checked = done(task.id)
                            ListItem(headlineContent={Text(task.name)},supportingContent={Text("${task.minutes} min${if (checked) " · Terminée" else ""}")},leadingContent={
                                Checkbox(checked=checked,onCheckedChange={checkedNow ->
                                    setDone(task.id, checkedNow)
                                    val current = session
                                    if (checkedNow && current?.taskId == task.id && !current.completed) advance()
                                    else if (!checkedNow && cut && !slept)
                                        setSession(Store.Session(today,task.id,System.currentTimeMillis()))
                                })
                            })
                        }
                        if (cut && !slept) item {
                            Button(onClick={finishNight()},modifier=Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Bedtime,null)
                                Spacer(Modifier.width(8.dp))
                                Text("Je vais dormir")
                            }
                        }
                        item { NotesPanel(tomorrow, journal, onTomorrow={updateNote("tomorrow", it)}, onJournal={updateNote("journal", it)}, initiallyExpanded=false) }
                        item { HorizontalDivider(modifier=Modifier.padding(vertical=12.dp)) }
                        item { Heading("Gérer les activités du soir", "Ajout, modification, réorganisation") }
                        items(cfg.tasks, key={it.id}) { task ->
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Checkbox(checked=task.enabled,onCheckedChange={v->save(cfg.copy(tasks=cfg.tasks.map{if(it.id==task.id)it.copy(enabled=v) else it}))})
                                    Column(Modifier.weight(1f)) { Text(task.name,fontWeight=FontWeight.SemiBold); Text("${task.minutes} min${if(task.optional) " · facultatif" else ""}",style=MaterialTheme.typography.bodySmall) }
                                    IconButton(onClick={editor=task;adding=false}) { Icon(Icons.Default.Edit,"Modifier") }
                                    IconButton(onClick={val list=cfg.tasks.toMutableList(); val i=list.indexOfFirst{it.id==task.id};if(i>0){ val x=list.removeAt(i);list.add(i-1,x);save(cfg.copy(tasks=list)) }}, enabled=cfg.tasks.firstOrNull()?.id!=task.id) { Icon(Icons.Default.KeyboardArrowUp,"Monter") }
                                    IconButton(onClick={val list=cfg.tasks.toMutableList();val i=list.indexOfFirst{it.id==task.id};if(i>=0 && i<list.lastIndex){val x=list.removeAt(i);list.add(i+1,x);save(cfg.copy(tasks=list))}},enabled=cfg.tasks.lastOrNull()?.id!=task.id) { Icon(Icons.Default.KeyboardArrowDown,"Descendre") }
                                }
                            }
                        }
                        item { OutlinedButton(onClick={adding=true;editor=null},modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Add,null); Text(" Ajouter une activité") } }
                        item { HorizontalDivider(modifier=Modifier.padding(vertical=12.dp)) }
                        item { Heading("Routine du matin", "Consignes et détails personnalisés pour le lever") }
                        item { SettingCard("Petite routine du lever") {
                            SwitchSetting("Activer la routine du matin", cfg.morningRoutine) { save(cfg.copy(morningRoutine=it)) }
                            if (cfg.morningRoutine) {
                                var morning1 by remember(cfg.morningStep1) { mutableStateOf(cfg.morningStep1) }
                                var morning2 by remember(cfg.morningStep2) { mutableStateOf(cfg.morningStep2) }
                                var detail1 by remember(cfg.morningDetail1) { mutableStateOf(cfg.morningDetail1) }
                                var detail2 by remember(cfg.morningDetail2) { mutableStateOf(cfg.morningDetail2) }
                                OutlinedTextField(value=morning1,onValueChange={morning1=it},label={Text("Première chose à faire au lever")},modifier=Modifier.fillMaxWidth())
                                OutlinedTextField(value=detail1,onValueChange={detail1=it},label={Text("Détails (facultatifs)")},
                                    placeholder={Text("Ex. 10 squats, 10 pompes, 5 min d'étirements")},
                                    minLines=2,maxLines=5,modifier=Modifier.fillMaxWidth())
                                OutlinedTextField(value=morning2,onValueChange={morning2=it},label={Text("Deuxième chose (facultative)")},modifier=Modifier.fillMaxWidth())
                                OutlinedTextField(value=detail2,onValueChange={detail2=it},label={Text("Détails (facultatifs)")},
                                    minLines=2,maxLines=5,modifier=Modifier.fillMaxWidth())
                                TextButton(onClick={save(cfg.copy(morningStep1=morning1.trim(),morningStep2=morning2.trim(),
                                    morningDetail1=detail1.trim(),morningDetail2=detail2.trim()))}) { Text("Enregistrer la routine du lever") }
                                Text("Le matin, chaque consigne apparaît avec sa légende sous le titre et sa case à cocher. Les détails restent facultatifs.",style=MaterialTheme.typography.bodySmall)
                            }
                        } }
                    }
                    2 -> {
                        item { Heading("Historique", "Les 90 dernières soirées enregistrées localement") }
                        item { Text("Ouvre une soirée pour consulter son journal et les priorités prévues.", style=MaterialTheme.typography.bodySmall) }
                        if(nights.isEmpty()) item { Text("Aucune soirée enregistrée pour le moment.") }
                        items(nights, key={it.date}) { night ->
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                                    Row(Modifier.fillMaxWidth().clickable{expandedHistory=if(expandedHistory==night.date) "" else night.date},verticalAlignment=Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(night.date,fontWeight=FontWeight.Bold)
                                            Text("Je coupe : ${night.cutAt.ifBlank{"—"}} · Je dors : ${night.sleptAt.ifBlank{"—"}}",style=MaterialTheme.typography.bodySmall)
                                        }
                                        Icon(if(expandedHistory==night.date) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,null)
                                    }
                                    if (expandedHistory == night.date) {
                                        HorizontalDivider()
                                        Text("Journal rapide",fontWeight=FontWeight.SemiBold)
                                        Text(store.getText("journal_${night.date}").ifBlank{"Aucune note"})
                                        Text("Priorités du lendemain",fontWeight=FontWeight.SemiBold)
                                        Text(store.getText("tomorrow_${night.date}").ifBlank{"Aucune priorité"})
                                        if(night.reason.isNotBlank()) Text("Dérapage : ${night.reason}")
                                        Row {
                                            TextButton(onClick={editingHistory=night.date}) { Text("Modifier les notes") }
                                            TextButton(onClick={reason=night.date}) { Text("Cause du retard") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        item { Heading("Paramètres", "Personnalise uniquement la fin de soirée") }
                        item { SettingCard("Horaires") {
                            TimeSetting("Début de déconnexion",cfg.start){save(cfg.copy(start=it))}
                            TimeSetting("Objectif coucher",cfg.bed){save(cfg.copy(bed=it))}
                            TimeSetting("Réveil / rétablissement",cfg.wake){save(cfg.copy(wake=it))}
                            Text("Jours actifs",fontWeight=FontWeight.SemiBold)
                            Row(horizontalArrangement=Arrangement.spacedBy(2.dp), modifier=Modifier.fillMaxWidth()) {
                                listOf("L","M","M","J","V","S","D").forEachIndexed { i, label ->
                                    FilterChip(selected=i+1 in cfg.days,onClick={save(cfg.copy(days=if(i+1 in cfg.days)cfg.days-(i+1) else cfg.days+(i+1)))},label={Text(label)}, modifier=Modifier.weight(1f))
                                }
                            }
                            SwitchSetting("Automatisations actives",cfg.enabled){save(cfg.copy(enabled=it))}
                        } }
                        item { SettingCard("Déconnexion numérique") {
                            SwitchSetting("Aide au noir et blanc (manuel)",cfg.gray){save(cfg.copy(gray=it));if(it)grayInfo=true}
                            Text("Android standard ne permet pas à OFFLINE de changer les couleurs du système sans accès privilégié. Utilise le mode Coucher / Correction des couleurs d'Android.",style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){Text("Ouvrir les réglages d'accessibilité")}
                            SwitchSetting("Ne pas déranger automatique",cfg.dnd){ requested ->
                                val manager=ctx.getSystemService(NotificationManager::class.java)
                                if (requested && !manager.isNotificationPolicyAccessGranted) {
                                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                                    Toast.makeText(ctx,"Autorise OFFLINE, puis active de nouveau l'option.",Toast.LENGTH_LONG).show()
                                } else save(cfg.copy(dnd=requested))
                            }
                            TextButton(onClick={ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))}){Text("Autoriser l'accès à Ne pas déranger")}
                            SwitchSetting("Ouvrir le mode avion en fin de routine",cfg.plane){save(cfg.copy(plane=it))}
                            if(cfg.plane) SwitchSetting("Demander confirmation avant ouverture",cfg.planeConfirm){save(cfg.copy(planeConfirm=it))}
                            Text("L'ouverture a lieu après « Je vais dormir » ; l'activation reste manuelle.",style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={ctx.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))}){Text("Tester l'ouverture du mode avion")}
                        } }
                        item { SettingCard("Progression des activités") {
                            Text("Passage entre les activités",fontWeight=FontWeight.SemiBold)
                            listOf("hybrid" to "Hybride : automatique sauf saisie", "auto" to "Automatique : toutes les activités", "manual" to "Manuel : confirmation à chaque étape").forEach { (value,label) ->
                                Row(Modifier.fillMaxWidth().clickable { save(cfg.copy(transition=value)) },verticalAlignment=Alignment.CenterVertically) {
                                    RadioButton(selected=cfg.transition==value,onClick={save(cfg.copy(transition=value))})
                                    Text(label,style=MaterialTheme.typography.bodyMedium)
                                }
                            }
                            SwitchSetting("Signal discret entre les activités",cfg.stepSound){save(cfg.copy(stepSound=it))}
                        } }
                        item { SettingCard("Distractions · surveillance intermédiaire") {
                            Text("Quota d'utilisation du soir : ${cfg.quota} minutes")
                            Slider(value=cfg.quota.toFloat(),onValueChange={save(cfg.copy(quota=it.toInt().coerceIn(5,60)))},valueRange=5f..60f,steps=10)
                            var pkgs by remember(cfg.packages) { mutableStateOf(cfg.packages) }
                            OutlinedTextField(value=pkgs,onValueChange={pkgs=it},label={Text("Identifiants Android des applications")}, supportingText={Text("Séparés par une virgule ; ex. com.twitter.android")},modifier=Modifier.fillMaxWidth())
                            TextButton(onClick={save(cfg.copy(packages=pkgs))}){Text("Enregistrer les applications")}
                            TextButton(onClick={ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))}){Text(if (usagePermitted(ctx)) "Accès aux statistiques autorisé ✓" else "Autoriser l'accès aux statistiques d'utilisation")}
                            Text("YouTube et Instagram font partie de la liste initiale. Pour une configuration déjà existante, ajoute-les manuellement si nécessaire.",style=MaterialTheme.typography.bodySmall)
                            Text("Avertissement uniquement : aucun blocage d'application.",style=MaterialTheme.typography.bodySmall)
                        } }
                        item { SettingCard("Rappels du soir") {
                            Text("Intensité des rappels",fontWeight=FontWeight.SemiBold)
                            listOf("normal" to "Normal · une notification", "persistent" to "Insistant · relances", "strict" to "Renforcé · toutes les 5 min, vibration appuyée et pop-up").forEach { (value,label) ->
                                Row(Modifier.fillMaxWidth().clickable{save(cfg.copy(reminderLevel=value))},verticalAlignment=Alignment.CenterVertically) {
                                    RadioButton(selected=cfg.reminderLevel==value,onClick={save(cfg.copy(reminderLevel=value))})
                                    Text(label,style=MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if(cfg.reminderLevel != "normal") {
                                Text(if (cfg.reminderLevel == "strict") "Relance toutes les 5 minutes (mode renforcé)" else "Relance toutes les ${cfg.repeatMinutes} minutes")
                                if (cfg.reminderLevel != "strict") listOf(5,10,15).forEach { mins ->
                                    Row(verticalAlignment=Alignment.CenterVertically) {
                                        RadioButton(selected=cfg.repeatMinutes==mins,onClick={save(cfg.copy(repeatMinutes=mins))})
                                        Text("$mins minutes",modifier=Modifier.clickable{save(cfg.copy(repeatMinutes=mins))})
                                    }
                                }
                            }
                            var title by remember(cfg.reminderTitle) { mutableStateOf(cfg.reminderTitle) }
                            var message by remember(cfg.reminderMessage) { mutableStateOf(cfg.reminderMessage) }
                            OutlinedTextField(value=title,onValueChange={title=it},label={Text("Titre du rappel")},modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(value=message,onValueChange={message=it},label={Text("Message du rappel")},modifier=Modifier.fillMaxWidth(),minLines=2)
                            TextButton(onClick={save(cfg.copy(reminderTitle=title.ifBlank{"OFFLINE"},reminderMessage=message))}){Text("Enregistrer les messages")}
                            SwitchSetting("Pop-up au déverrouillage",cfg.unlockPopup){v->save(cfg.copy(unlockPopup=v, popupOverlay=if(v) cfg.popupOverlay else false))}
                            if(cfg.unlockPopup) {
                                SwitchSetting("Fenêtre par-dessus les applications",cfg.popupOverlay){ v ->
                                    if (v && !Settings.canDrawOverlays(ctx)) {
                                        runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${ctx.packageName}"))) }
                                        Toast.makeText(ctx,"Autorise la superposition puis reviens activer l'option.",Toast.LENGTH_LONG).show()
                                    } else save(cfg.copy(popupOverlay=v))
                                }
                                Text("Une autorisation Android est nécessaire. La notification de service n’apparaît que pendant la plage du soir, jusqu’à 4 h maximum. Android peut retarder son démarrage automatique.",style=MaterialTheme.typography.bodySmall)
                            }
                            Text("Après « Je coupe » ou « Ignorer ce soir », les rappels cessent. En mode renforcé, le pop-up nécessite toujours ton autorisation ; les sons et vibrations dépendent des réglages Android.",style=MaterialTheme.typography.bodySmall)
                        } }
                        item { SettingCard("Notifications et données") {
                            SwitchSetting("Rappel des priorités du lendemain",cfg.morning){save(cfg.copy(morning=it))}
                            Text("Les activités et l'historique restent sur ce téléphone.",style=MaterialTheme.typography.bodySmall)
                            var confirm by remember { mutableStateOf(false) }
                            OutlinedButton(onClick={confirm=true}){Text("Effacer toutes les données")}
                            if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Effacer OFFLINE ?")},text={Text("Routine, paramètres et historique seront supprimés.")},confirmButton={TextButton(onClick={store.clear();cfg=store.load();nights=emptyList();confirm=false}){Text("Effacer")}},dismissButton={TextButton(onClick={confirm=false}){Text("Annuler")}})
                        } }
                    }
                }
            }
        }
        if (confirmPlane) AlertDialog(onDismissRequest={confirmPlane=false},title={Text("Activer le mode avion ?")},text={Text("OFFLINE a terminé la soirée. Ouvre les réglages et active manuellement le mode avion avant de poser le téléphone.")},confirmButton={TextButton(onClick={confirmPlane=false;ctx.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))}){Text("Ouvrir les réglages")}},dismissButton={TextButton(onClick={confirmPlane=false}){Text("Ignorer")}})
        if (grayInfo) AlertDialog(onDismissRequest={grayInfo=false},title={Text("Niveaux de gris")},text={Text("Ce réglage ne peut pas être activé automatiquement par une application Android ordinaire. Configure le mode Coucher dans les réglages Android pour automatiser l'écran noir et blanc.")},confirmButton={TextButton(onClick={grayInfo=false;ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){Text("Réglages")}},dismissButton={TextButton(onClick={grayInfo=false}){Text("Plus tard")}})
        if (adding || editor != null) {
            var name by remember(editor,adding) { mutableStateOf(editor?.name ?: "") }
            var minutes by remember(editor,adding) { mutableStateOf((editor?.minutes ?: 5).toString()) }
            var optional by remember(editor,adding) { mutableStateOf(editor?.optional ?: false) }
            AlertDialog(onDismissRequest={adding=false;editor=null},title={Text(if(adding)"Ajouter une activité" else "Modifier l'activité")},text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value=name,onValueChange={name=it},label={Text("Nom")})
                    OutlinedTextField(value=minutes,onValueChange={minutes=it},label={Text("Durée (minutes)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                    SwitchSetting("Facultative",optional){optional=it}
                    if(editor != null) TextButton(onClick={val id=editor!!.id;save(cfg.copy(tasks=cfg.tasks.filter{it.id!=id}));editor=null}){Text("Supprimer l'activité")}
                }
            },confirmButton={TextButton(enabled=name.isNotBlank() && (minutes.toIntOrNull() ?: 0) in 1..180,onClick={
                val task=Task(editor?.id ?: (cfg.tasks.maxOfOrNull{it.id} ?: 0L)+1,name.trim(),minutes.toInt(),editor?.enabled ?: true,optional)
                save(cfg.copy(tasks=if(adding)cfg.tasks+task else cfg.tasks.map{if(it.id==task.id)task else it}));adding=false;editor=null
            }){Text("Enregistrer")}},dismissButton={TextButton(onClick={adding=false;editor=null}){Text("Annuler")}})
        }
        if(editingHistory.isNotBlank()) {
            val date = editingHistory
            var historicJournal by remember(date) { mutableStateOf(store.getText("journal_$date")) }
            var historicTomorrow by remember(date) { mutableStateOf(store.getText("tomorrow_$date")) }
            AlertDialog(onDismissRequest={editingHistory=""},title={Text("Notes du $date")},text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value=historicJournal,onValueChange={historicJournal=it},label={Text("Journal rapide")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(value=historicTomorrow,onValueChange={historicTomorrow=it},label={Text("Priorités du lendemain")},modifier=Modifier.fillMaxWidth())
                }
            },confirmButton={TextButton(onClick={updateNote("journal",historicJournal,date);updateNote("tomorrow",historicTomorrow,date);editingHistory=""}){Text("Enregistrer")}},
            dismissButton={TextButton(onClick={editingHistory=""}){Text("Annuler")}})
        }
        if(reason.isNotBlank()) {
            var selected by remember(reason) { mutableStateOf(nights.firstOrNull{it.date==reason}?.reason ?: "") }
            AlertDialog(onDismissRequest={reason=""},title={Text("Pourquoi ce coucher tardif ?")},text={
                Column { listOf("Aucun dérapage","PC","Réseaux sociaux","Vidéos","Porno","Travail","Sortie","Autre").forEach { option ->
                    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.clickable{selected=option}) { RadioButton(selected=selected==option,onClick={selected=option});Text(option) }
                } }
            },confirmButton={TextButton(onClick={val date=reason;store.updateNight(date){it.copy(reason=selected)};nights=store.nights();reason=""}){Text("Enregistrer")}},dismissButton={TextButton(onClick={reason=""}){Text("Annuler")}})
        }
    }
}

@Composable private fun NotesPanel(tomorrow: String, journal: String, onTomorrow: (String)->Unit, onJournal: (String)->Unit, initiallyExpanded: Boolean = false) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable{expanded=!expanded},verticalAlignment=Alignment.CenterVertically) {
                Text("Notes du soir",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                Icon(if(expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,null)
            }
            if (!expanded) {
                Text(if(tomorrow.isBlank() && journal.isBlank()) "Journal et priorités accessibles à tout moment" else "Notes enregistrées · appuie pour les modifier",style=MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(value=tomorrow,onValueChange=onTomorrow,label={Text("Priorités de demain")},modifier=Modifier.fillMaxWidth(),minLines=2)
                OutlinedTextField(value=journal,onValueChange=onJournal,label={Text("Journal rapide")},modifier=Modifier.fillMaxWidth(),minLines=2)
                Text("Enregistrement automatique · les notes restent dans l'historique",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun Heading(title: String, subtitle: String) {
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text(title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SettingCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            HorizontalDivider(); content()
        }
    }
}

@Composable private fun SwitchSetting(title:String,enabled:Boolean,onChange:(Boolean)->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
        Text(title,modifier=Modifier.weight(1f));Switch(checked=enabled,onCheckedChange=onChange)
    }
}

@Composable private fun TimeSetting(label:String,value:String,onChange:(String)->Unit) {
    val ctx=LocalContext.current
    val current=runCatching{LocalTime.parse(value)}.getOrDefault(LocalTime.of(23,30))
    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
        Text(label,modifier=Modifier.weight(1f));OutlinedButton(onClick={TimePickerDialog(ctx,{_,h,m->onChange("%02d:%02d".format(h,m))},current.hour,current.minute,true).show()}){Text(value)}
    }
}
