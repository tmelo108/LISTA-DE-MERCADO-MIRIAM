package ai.autovideo.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AspectRatio(val label: String) {
    @SerialName("16_9") LANDSCAPE("16:9"),
    @SerialName("9_16") PORTRAIT("9:16"),
    @SerialName("1_1") SQUARE("1:1")
}

@Serializable
enum class OutputResolution(val label: String, val shortEdge: Int) {
    HD_720("720p", 720),
    FULL_HD_1080("1080p", 1080)
}

@Serializable
enum class VideoCodec(val label: String) {
    H264("H.264"), H265("H.265")
}

@Serializable
enum class DistributionMode(val label: String) {
    AUTOMATIC("Distribuição automática"),
    ONE_PER_SCENE("Uma mídia por microcena"),
    PRIORITIZE_VIDEO("Priorizar vídeos"),
    PRIORITIZE_IMAGE("Priorizar imagens"),
    ALTERNATE("Alternar vídeo / imagem"),
    PROPORTIONAL("Distribuir proporcionalmente")
}

@Serializable
enum class TakeDurationMode(val label: String) {
    AUTOMATIC("Automática"), TWO("2 s"), THREE("3 s"), FOUR("4 s"), FIVE("5 s"),
    SIX("6 s"), EIGHT("8 s"), CUSTOM("Personalizada");

    fun fixedSeconds(): Int? = when (this) {
        TWO -> 2; THREE -> 3; FOUR -> 4; FIVE -> 5; SIX -> 6; EIGHT -> 8
        AUTOMATIC, CUSTOM -> null
    }
}

@Serializable
enum class VisualStyle(val label: String, val searchSuffix: String) {
    CINEMATIC("Cinematográfico", "cinematic lighting cinematic shot"),
    DOCUMENTARY("Documentário", "documentary natural footage"),
    STORYTELLING("Storytelling", "storytelling emotional cinematic"),
    NEWS("Notícias", "news reportage editorial"),
    MOTIVATIONAL("Motivacional", "inspirational uplifting"),
    GOSPEL("Gospel", "faith hope peaceful light"),
    CURIOSITIES("Curiosidades", "educational interesting detail"),
    AUTOMATIC("Automático", "")
}

@Serializable
enum class SubtitleMode(val label: String) {
    SRT("Arquivo SRT"), BURNED_IN("Texto queimado no vídeo")
}

@Serializable
enum class TransitionKind(val label: String) {
    AUTOMATIC("Automático"), FADE("Fade"), CROSSFADE("Crossfade"), DISSOLVE("Dissolve"),
    SLIDE("Slide"), WIPE("Wipe"), ZOOM("Zoom"), NONE("Nenhuma")
}

@Serializable
data class ProjectSettings(
    val videoCount: Int = 10,
    val imageCount: Int = 5,
    val aspectRatio: AspectRatio = AspectRatio.LANDSCAPE,
    val resolution: OutputResolution = OutputResolution.HD_720,
    val fps: Int = 30,
    val codec: VideoCodec = VideoCodec.H264,
    val distributionMode: DistributionMode = DistributionMode.AUTOMATIC,
    val allowRepeat: Boolean = false,
    val takeMode: TakeDurationMode = TakeDurationMode.AUTOMATIC,
    val customTakeMinSeconds: Int = 3,
    val customTakeMaxSeconds: Int = 7,
    val visualStyle: VisualStyle = VisualStyle.STORYTELLING,
    val kenBurnsEnabled: Boolean = true,
    val kenBurnsIntensity: Int = 20,
    val kenBurnsMovement: String = "AUTOMATIC",
    val smoothVideoZoom: Boolean = true,
    val smoothVideoPan: Boolean = true,
    val cinematicVideoMotion: Boolean = true,
    val videoMotionIntensity: Int = 20,
    val transitionsEnabled: Boolean = false,
    val transitionKind: TransitionKind = TransitionKind.NONE,
    val transitionDurationMs: Int = 300,
    val narrationVolume: Int = 100,
    val backgroundMusicEnabled: Boolean = false,
    val backgroundMusicPath: String? = null,
    val backgroundMusicVolume: Int = 10,
    val musicFadeIn: Boolean = false,
    val musicFadeOut: Boolean = false,
    val subtitlesEnabled: Boolean = false,
    val subtitleMode: SubtitleMode = SubtitleMode.SRT,
    val subtitlePosition: String = "BOTTOM",
    val subtitleFontSizeSp: Int = 22,
    val subtitleColor: String = "#FFFFFF",
    val subtitleOutlinePx: Int = 2,
    val subtitleBackground: Boolean = false
) {
    companion object {
        fun storytelling() = ProjectSettings(
            videoCount = 20,
            imageCount = 10,
            aspectRatio = AspectRatio.LANDSCAPE,
            resolution = OutputResolution.FULL_HD_1080,
            fps = 30,
            codec = VideoCodec.H264,
            distributionMode = DistributionMode.AUTOMATIC,
            kenBurnsEnabled = true,
            kenBurnsIntensity = 20,
            transitionsEnabled = false,
            transitionKind = TransitionKind.NONE,
            transitionDurationMs = 500,
            narrationVolume = 100,
            backgroundMusicEnabled = false,
            backgroundMusicVolume = 10,
            visualStyle = VisualStyle.STORYTELLING
        )
    }
}

@Serializable
data class SceneDraft(
    val id: Int,
    val start: Double,
    val end: Double,
    val duration: Double,
    val narration: String,
    @SerialName("visual_description") val visualDescription: String,
    @SerialName("search_queries") val searchQueries: List<String> = emptyList(),
    @SerialName("preferred_media") val preferredMedia: String = "video"
)

@Serializable
data class ScriptAnalysis(
    val transcript: String,
    val scenes: List<SceneDraft>
)

@Serializable
data class SceneDocument(val scenes: List<SceneDraft>)

@Serializable
data class ProjectPreset(
    val name: String,
    val settingsJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ApiProvider(val title: String, val officialUrl: String, val helperText: String) {
    GEMINI("Gemini API", "https://aistudio.google.com/apikey", "Use o Google AI Studio para criar sua chave Gemini."),
    PEXELS("Pexels", "https://www.pexels.com/api/", "Chave gratuita para pesquisa de fotos e vídeos licenciados."),
    PIXABAY("Pixabay", "https://pixabay.com/api/docs/", "Chave gratuita para pesquisa de fotos e vídeos."),
    YOUTUBE("YouTube Data API v3", "https://console.cloud.google.com/", "Crie um projeto no Google Cloud e ative a YouTube Data API v3.")
}

enum class MediaKind(val label: String) {
    VIDEO("Vídeo"), IMAGE("Imagem")
}
