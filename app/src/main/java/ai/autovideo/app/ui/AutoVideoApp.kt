package ai.autovideo.app.ui

import ai.autovideo.app.data.local.ProjectEntity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class AppTab(val label: String) { HOME("Início"), PROJECTS("Projetos"), LIBRARY("Biblioteca"), SETTINGS("Ajustes") }
private enum class AppPage { HOME, PROJECTS, LIBRARY, SETTINGS, APIS, CREATE, DETAIL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoVideoApp(viewModel: MainViewModel = viewModel()) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val apiTests by viewModel.apiTests.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val searchState by viewModel.search.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(AppPage.HOME) }
    var selectedProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            snackbarHost.showSnackbar(message!!)
            viewModel.dismissMessage()
        }
    }

    val title = when (page) {
        AppPage.HOME -> "AutoVideo AI"
        AppPage.PROJECTS -> "Projetos"
        AppPage.LIBRARY -> "Meu banco de mídia"
        AppPage.SETTINGS -> "Configurações"
        AppPage.APIS -> "APIs e integrações"
        AppPage.CREATE -> "Novo projeto"
        AppPage.DETAIL -> projects.firstOrNull { it.id == selectedProjectId }?.name ?: "Projeto"
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, fontWeight = FontWeight.SemiBold)
                        if (page == AppPage.HOME) Text("EDIÇÃO LOCAL NO ANDROID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                },
                navigationIcon = {
                    if (page == AppPage.CREATE || page == AppPage.DETAIL || page == AppPage.APIS) {
                        androidx.compose.material3.IconButton(onClick = {
                            page = when (page) {
                                AppPage.APIS -> AppPage.SETTINGS
                                AppPage.DETAIL -> AppPage.PROJECTS
                                else -> AppPage.HOME
                            }
                        }) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                    } else {
                        Box(
                            Modifier.padding(start = 14.dp, end = 6.dp).size(34.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.padding(7.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            if (page != AppPage.CREATE && page != AppPage.DETAIL && page != AppPage.APIS) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val selectedTab = when (page) {
                        AppPage.HOME -> AppTab.HOME
                        AppPage.PROJECTS -> AppTab.PROJECTS
                        AppPage.LIBRARY -> AppTab.LIBRARY
                        else -> AppTab.SETTINGS
                    }
                    NavigationBarItem(
                        selected = selectedTab == AppTab.HOME,
                        onClick = { page = AppPage.HOME },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text(AppTab.HOME.label) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.PROJECTS,
                        onClick = { page = AppPage.PROJECTS },
                        icon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                        label = { Text(AppTab.PROJECTS.label) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.LIBRARY,
                        onClick = { page = AppPage.LIBRARY },
                        icon = {
                            BadgedBox(badge = { if (library.isNotEmpty()) Badge { Text(library.size.toString()) } }) {
                                Icon(Icons.Filled.VideoLibrary, contentDescription = null)
                            }
                        },
                        label = { Text(AppTab.LIBRARY.label) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.SETTINGS,
                        onClick = { page = AppPage.SETTINGS },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text(AppTab.SETTINGS.label) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when (page) {
                AppPage.HOME -> HomeScreen(
                    projects = projects,
                    onCreate = { page = AppPage.CREATE },
                    onOpenProject = { selectedProjectId = it; page = AppPage.DETAIL },
                    onSeeAll = { page = AppPage.PROJECTS },
                    onOpenSettings = { page = AppPage.SETTINGS }
                )
                AppPage.PROJECTS -> ProjectsScreen(
                    projects = projects,
                    onOpen = { selectedProjectId = it; page = AppPage.DETAIL },
                    onCreate = { page = AppPage.CREATE },
                    onDelete = { viewModel.deleteProject(it) }
                )
                AppPage.CREATE -> CreateProjectScreen(
                    viewModel = viewModel,
                    presets = presets,
                    onBack = { page = AppPage.HOME },
                    onCreated = { id -> selectedProjectId = id; page = AppPage.DETAIL }
                )
                AppPage.DETAIL -> selectedProjectId?.let { id ->
                    ProjectDetailScreen(
                        projectId = id,
                        viewModel = viewModel,
                        searchState = searchState,
                        onCreateAnother = { page = AppPage.CREATE }
                    )
                } ?: EmptyState("Projeto não encontrado.")
                AppPage.LIBRARY -> LibraryScreen(library, viewModel)
                AppPage.SETTINGS -> SettingsScreen(
                    onOpenApis = { page = AppPage.APIS },
                    onOpenLibrary = { page = AppPage.LIBRARY }
                )
                AppPage.APIS -> ApiSettingsScreen(viewModel, apiTests)
            }
        }
    }
}

@Composable
fun HomeScreen(
    projects: List<ProjectEntity>,
    onCreate: () -> Unit,
    onOpenProject: (String) -> Unit,
    onSeeAll: () -> Unit,
    onOpenSettings: () -> Unit
) {
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { HeroCard(onCreate) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Seus projetos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Continue de onde parou", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.TextButton(onClick = onSeeAll) { Text("Ver todos") }
            }
        }
        if (projects.isEmpty()) {
            item { EmptyProjectsCard(onCreate, onOpenSettings) }
        } else {
            items(projects.take(4), key = { it.id }) { project ->
                ProjectCard(project = project, onClick = { onOpenProject(project.id) })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                QuickFeatureCard("IA + roteiro", "Gemini", Icons.Filled.AutoAwesome, Modifier.weight(1f))
                QuickFeatureCard("Exportação", "No aparelho", Icons.Filled.VideoLibrary, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EmptyProjectsCard(onCreate: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tudo começa com uma voz.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Adicione a narração, configure quantos vídeos e imagens quer usar e deixe o AutoVideo montar a timeline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.TextButton(onClick = onCreate) { Text("Criar projeto") }
                androidx.compose.material3.TextButton(onClick = onOpenSettings) { Text("Configurar APIs") }
            }
        }
    }
}

@Composable
private fun QuickFeatureCard(title: String, caption: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ProjectsScreen(
    projects: List<ProjectEntity>,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit
) {
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionHeader("Todos os projetos", "${projects.size} salvo(s) neste aparelho")
            Spacer(Modifier.height(10.dp))
            PrimaryButton("＋  Novo projeto", onCreate)
        }
        if (projects.isEmpty()) item { EmptyState("Os projetos criados e processados aparecerão aqui.") }
        items(projects, key = { it.id }) { project ->
            ProjectCard(project, onClick = { onOpen(project.id) }, onDelete = { onDelete(project.id) })
        }
    }
}

@Composable
fun SettingsScreen(onOpenApis: () -> Unit, onOpenLibrary: () -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Seu estúdio, no seu aparelho.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Chaves de API ficam criptografadas no Android Keystore. A montagem e a exportação acontecem localmente com Media3 Transformer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { ActionCard("APIs e integrações", "Gemini · Pexels · Pixabay · YouTube", Icons.Filled.AutoAwesome, onOpenApis) }
        item { ActionCard("Meu banco de mídia", "Importe arquivos próprios e encontre-os por tags do nome", Icons.Filled.VideoLibrary, onOpenLibrary) }
        item {
            InfoCard(
                title = "Limitações transparentes desta versão",
                body = "Transições entre clipes e legendas queimadas não são exportadas nesta versão. O SRT é gerado separadamente; Ken Burns, cortes, enquadramento, áudio da narração e MP4 local são suportados."
            )
        }
        item {
            InfoCard(
                title = "Uso responsável",
                body = "YouTube serve apenas para pesquisa e referência. O AutoVideo AI não baixa vídeos do YouTube. Use arquivos com licença compatível com seu projeto."
            )
        }
    }
}
