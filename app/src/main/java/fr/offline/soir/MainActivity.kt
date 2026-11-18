package fr.offline.soir

import android.Manifest
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
        setContent { OfflineApp() }
    }
}

private val fmt = DateTimeFormatter.ofPattern("HH:mm")
private fun nowText() = LocalTime.now().format(fmt)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineApp() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var cfg by remember { mutableStateOf(store.load()) }
    var nights by remember { mutableStateOf(store.nights()) }
    var page by remember { mutableStateOf(0) }
    var editor by remember { mutableStateOf<Task?>(null) }
    var adding by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var cut by remember { mutableStateOf(false) }
    var slept by remember { mutableStateOf(false) }
    val today = LocalDate.now().toString()
    LaunchedEffect(page) {
        nights = store.nights()
        cut = nights.any { it.date == today && it.cutAt.isNotBlank() }
        slept = nights.any { it.date == today && it.sleptAt.isNotBlank() }
    }
    fun save(c: Config) { cfg = c; store.save(c) }
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
                                    if (!cut && !slept) Button(onClick={
                                        store.updateNight { it.copy(cutAt=nowText()) }; cut=true; nights=store.nights(); page=1
                                        if (cfg.gray) Alerts.notify(ctx,201,"OFFLINE · Noir et blanc","Active le niveau de gris depuis les réglages d'accessibilité.")
                                        if (cfg.plane) Alerts.notify(ctx,202,"OFFLINE · Mode avion","Active le mode avion manuellement si tu le souhaites.")
                                    },modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.PowerSettingsNew,null); Spacer(Modifier.width(8.dp)); Text("Je coupe") }
                                    if (cut && !slept) Button(onClick={
                                        store.updateNight { it.copy(sleptAt=nowText()) }; slept=true; nights=store.nights()
                                    },modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Bedtime,null); Spacer(Modifier.width(8.dp)); Text("Je vais dormir") }
                                }
                            }
                        }
                        item { Heading("Routine prévue", "${cfg.tasks.filter{it.enabled}.sumOf{it.minutes}} minutes d'activités") }
                        items(cfg.tasks.filter{it.enabled}) { t -> ListItem(headlineContent={Text(t.name)}, supportingContent={Text("${t.minutes} min${if(t.optional) " · facultatif" else ""}")}, leadingContent={Icon(Icons.Default.CheckCircleOutline,null)}) }
                        item { Text("Le noir et blanc et le mode avion nécessitent une action système distincte ; OFFLINE ne modifie pas ces réglages automatiquement.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    1 -> {
                        item { Heading("Mes activités", "Active, renomme et réorganise ta routine") }
                        item { Text("Coche les étapes terminées. Les modifications restent accessibles à tout moment.",style=MaterialTheme.typography.bodySmall) }
                        items(cfg.tasks.filter { it.enabled }, key={"check_"+it.id}) { task ->
                            var done by remember(task.id,today) { mutableStateOf(store.getBoolean("done_${today}_${task.id}")) }
                            ListItem(headlineContent={Text(task.name)},supportingContent={Text("${task.minutes} min")},leadingContent={
                                Checkbox(checked=done,onCheckedChange={done=it;store.setBoolean("done_${today}_${task.id}",it)})
                            })
                        }
                        item { SettingCard("Notes du soir") {
                            var tomorrow by remember { mutableStateOf(store.getText("tomorrow_$today")) }
                            var journal by remember { mutableStateOf(store.getText("journal_$today")) }
                            OutlinedTextField(value=tomorrow,onValueChange={tomorrow=it;store.setText("tomorrow_$today",it)},label={Text("Priorités de demain")},modifier=Modifier.fillMaxWidth(),minLines=2)
                            OutlinedTextField(value=journal,onValueChange={journal=it;store.setText("journal_$today",it)},label={Text("Journal rapide")},modifier=Modifier.fillMaxWidth(),minLines=2)
                        } }
                        item { Heading("Gérer les activités", "Ajout, modification, réorganisation") }
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
                        if (cut && !slept) item { Button(onClick={store.updateNight { it.copy(sleptAt=nowText()) };slept=true;nights=store.nights();page=0},modifier=Modifier.fillMaxWidth()) { Text("Je vais dormir") } }
                    }
                    2 -> {
                        item { Heading("Historique", "Les 90 dernières soirées enregistrées localement") }
                        item { Text("Appuie sur une soirée pour renseigner la cause d'un coucher tardif.", style=MaterialTheme.typography.bodySmall) }
                        if(nights.isEmpty()) item { Text("Aucune soirée enregistrée pour le moment.") }
                        items(nights) { night ->
                            ElevatedCard(onClick={reason=night.date},modifier=Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(night.date,fontWeight=FontWeight.Bold)
                                    Text("Je coupe : ${night.cutAt.ifBlank{"—"}}  ·  Je dors : ${night.sleptAt.ifBlank{"—"}}")
                                    if(night.reason.isNotBlank())Text("Dérapage : ${night.reason}",style=MaterialTheme.typography.bodySmall)
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
                            SwitchSetting("Proposer le noir et blanc",cfg.gray){save(cfg.copy(gray=it))}
                            TextButton(onClick={ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){Text("Ouvrir les réglages de niveaux de gris")}
                            SwitchSetting("Ne pas déranger automatique",cfg.dnd){save(cfg.copy(dnd=it))}
                            TextButton(onClick={ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))}){Text("Autoriser l'accès à Ne pas déranger")}
                            SwitchSetting("Proposer le mode avion",cfg.plane){save(cfg.copy(plane=it))}
                            TextButton(onClick={ctx.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))}){Text("Ouvrir le réglage du mode avion")}
                            SwitchSetting("Rappel fin du PC",cfg.reminder){save(cfg.copy(reminder=it))}
                        } }
                        item { SettingCard("Distractions · surveillance intermédiaire") {
                            Text("Quota d'utilisation du soir : ${cfg.quota} minutes")
                            Slider(value=cfg.quota.toFloat(),onValueChange={save(cfg.copy(quota=it.toInt().coerceIn(5,60)))},valueRange=5f..60f,steps=10)
                            var pkgs by remember(cfg.packages) { mutableStateOf(cfg.packages) }
                            OutlinedTextField(value=pkgs,onValueChange={pkgs=it},label={Text("Identifiants Android des applications")}, supportingText={Text("Séparés par une virgule ; ex. com.twitter.android")},modifier=Modifier.fillMaxWidth())
                            TextButton(onClick={save(cfg.copy(packages=pkgs))}){Text("Enregistrer les applications")}
                            TextButton(onClick={ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))}){Text(if (usagePermitted(ctx)) "Accès aux statistiques autorisé ✓" else "Autoriser l'accès aux statistiques d'utilisation")}
                            Text("Avertissement uniquement : aucun blocage d'application.",style=MaterialTheme.typography.bodySmall)
                        } }
                        item { SettingCard("Notifications et données") {
                            SwitchSetting("Bilan du lendemain",cfg.morning){save(cfg.copy(morning=it))}
                            Text("Les activités et l'historique restent sur ce téléphone.",style=MaterialTheme.typography.bodySmall)
                            var confirm by remember { mutableStateOf(false) }
                            OutlinedButton(onClick={confirm=true}){Text("Effacer toutes les données")}
                            if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Effacer OFFLINE ?")},text={Text("Routine, paramètres et historique seront supprimés.")},confirmButton={TextButton(onClick={store.clear();cfg=store.load();nights=emptyList();confirm=false}){Text("Effacer")}},dismissButton={TextButton(onClick={confirm=false}){Text("Annuler")}})
                        } }
                    }
                }
            }
        }
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
