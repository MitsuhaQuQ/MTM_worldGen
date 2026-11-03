package amidst.mojangapi.minecraftinterface.mtm;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import amidst.documentation.Immutable;
import amidst.mojangapi.file.LauncherProfile;
import amidst.mojangapi.minecraftinterface.MinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceCreationException;

/**
 * Runs Amidst on the MTM Worldgen mod alone, without a Minecraft installation.
 *
 * The game directory, when given, provides the saves, the config file for worlds from a seed and the mod jar in its
 * mods folder.
 */
@Immutable
public class MtmLauncherProfile extends LauncherProfile {
	private static final String MOD_JAR_PREFIX = "mtm_worldgen-";

	/**
	 * The mod jar in the mods folder of a game directory, if there is one. Development jars, which are not
	 * remapped, and source jars are skipped.
	 */
	public static Optional<Path> findModJar(Path gameDirectory) {
		Path mods = gameDirectory.resolve("mods");
		if (!Files.isDirectory(mods)) {
			return Optional.empty();
		}
		try (Stream<Path> files = Files.list(mods)) {
			return files.filter(MtmLauncherProfile::isModJar).max(Comparator.comparing(Path::getFileName));
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	private static boolean isModJar(Path file) {
		String name = file.getFileName().toString();
		return name.startsWith(MOD_JAR_PREFIX) && name.endsWith(".jar")
				&& !name.endsWith("-sources.jar") && !name.contains("-dev");
	}

	/**
	 * @param modJar        the mod jar, or null to take it from the mods folder of the game directory
	 * @param gameDirectory the game directory, or null to use the working directory
	 */
	public static MtmLauncherProfile create(Path modJar, Path gameDirectory) {
		Path directory = gameDirectory != null ? gameDirectory : Path.of("").toAbsolutePath();
		return new MtmLauncherProfile(modJar != null ? Optional.of(modJar) : findModJar(directory), directory);
	}

	private final Optional<Path> modJar;
	private final Path gameDirectory;

	private MtmLauncherProfile(Optional<Path> modJar, Path gameDirectory) {
		super(null, null, null, null, true, "MTM Worldgen");
		this.modJar = modJar;
		this.gameDirectory = gameDirectory;
	}

	public MinecraftInterface createInterface() throws MinecraftInterfaceCreationException {
		if (!modJar.isPresent()) {
			throw new MinecraftInterfaceCreationException(
					"No MTM Worldgen jar in " + gameDirectory.resolve("mods") + ", choose one with -mtmjar");
		}
		if (!Files.isRegularFile(modJar.get())) {
			throw new MinecraftInterfaceCreationException("MTM Worldgen jar not found: " + modJar.get());
		}
		return MtmMinecraftInterface.create(modJar.get(), gameDirectory);
	}

	@Override
	public String getVersionId() {
		return modJar.map(jar -> jar.getFileName().toString()).orElse("MTM Worldgen");
	}

	@Override
	public String getVersionName() {
		return getVersionId();
	}

	@Override
	public Path getJar() {
		return modJar.orElse(null);
	}

	@Override
	public Path getSaves() {
		return gameDirectory.resolve("saves");
	}

	@Override
	public URLClassLoader newClassLoader() {
		throw new UnsupportedOperationException("MTM Worldgen runs without Minecraft");
	}
}
