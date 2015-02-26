package at.metalab.m68k.selfiebot;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.comparator.LastModifiedFileComparator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import at.metalab.m68k.soup.NotAuthorizedException;
import at.metalab.m68k.soup.OAuthHelper;
import at.metalab.m68k.soup.SoupClient;
import at.metalab.m68k.soup.SoupClientImpl;
import at.metalab.m68k.soup.resource.Blog;
import at.metalab.m68k.soup.resource.PostResult;
import at.metalab.m68k.soup.resource.User;
import at.metalab.m68k.soup.resource.posts.Image;

public class SelfiebotMain {

	private final static Logger LOG = LogManager.getLogger(SelfiebotMain.class);

	private static Options buildOptions() {
		Options options = new Options();

		@SuppressWarnings("static-access")
		Option tags = OptionBuilder.withArgName("tags").hasArg()
				.withDescription("tags (seperated by blanks)").create("tags");
		options.addOption(tags);

		@SuppressWarnings("static-access")
		Option blog = OptionBuilder.withArgName("name").hasArg().isRequired()
				.withDescription("blog to post").create("blog");
		options.addOption(blog);

		@SuppressWarnings("static-access")
		Option description = OptionBuilder.withArgName("text").hasArg()
				.withDescription("description of the post")
				.create("description");
		options.addOption(description);

		@SuppressWarnings("static-access")
		Option source = OptionBuilder.withArgName("link").hasArg()
				.withDescription("link used as the source of the post")
				.create("source");
		options.addOption(source);

		@SuppressWarnings("static-access")
		Option inputDir = OptionBuilder
				.withArgName("directory")
				.hasArg()
				.isRequired()
				.withDescription(
						"directory which will be scanned for files to upload")
				.create("input_dir");
		options.addOption(inputDir);

		return options;
	}

	private static void printUsage(Options options) {
		printUsage(options, null, null);
	}

	private static void printUsage(Options options,
			ParseException parseException) {
		printUsage(options, parseException, null);
	}

	private static void printUsage(Options options, String message) {
		printUsage(options, null, message);
	}

	private static void printUsage(Options options,
			ParseException parseException, String message) {
		if (message != null) {
			System.out.println(String.format("Error: %s!", message));
		}
		if (parseException != null) {
			System.out.println(parseException.getMessage());
		}
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp(120, "selfiebot", "Selfiebot soup.io uploader",
				options, "");
	}

	private static String emptyIfNull(String string) {
		if (string == null) {
			return "";
		}

		return string;
	}

	public static void main(String[] args) throws Exception {
		CommandLine line = null;
		Options options = buildOptions();

		try {
			CommandLineParser parser = new GnuParser();
			line = parser.parse(options, args);
		} catch (ParseException parseException) {
			printUsage(options, parseException);
			return;
		}

		if (line.hasOption("help")) {
			printUsage(options);
			return;
		}

		File sourceDir = new File(line.getOptionValue("input_dir"));
		String blogName = line.getOptionValue("blog");
		String tags = emptyIfNull(line.getOptionValue("tags"));
		String source = emptyIfNull(line.getOptionValue("source"));
		String description = emptyIfNull(line.getOptionValue("description"));

		File atticDir = new File(sourceDir, "attic");
		atticDir.mkdir();

		// soup client setup
		Properties soupApiProperties = OAuthHelper.loadApiProperties();
		Properties accessTokenProperties = OAuthHelper
				.loadAccessTokenProperties();

		SoupClient soup = new SoupClientImpl(soupApiProperties,
				accessTokenProperties, 1337);

		User user = null;

		do {
			try {
				user = soup.getUser();
			} catch (NotAuthorizedException notAuthorizedException) {
				LOG.error("Not authorized. Giving up.");
				return;
			} catch (NullPointerException nullPointerException) {
				// this may happen if soup has issues ...
				LOG.error("Authentication failed, will try again in a few seconds.");
				Thread.sleep(5000);
			}
		} while (user == null);

		LOG.info(String.format("Selfiebot has connected as '%s'",
				user.getName()));

		Blog blog = null;

		// select the blog which represents the users own soup
		for (Blog writeableBlog : user.getBlogs()) {
			if (writeableBlog.getName().equals(blogName)) {
				blog = writeableBlog;
				break;
			}
		}

		if (blog == null) {
			LOG.error("Blog not found, typo or insufficient rights?");
			System.exit(1);
			return;
		}

		LOG.info(String.format("Posting images in '%s' to soup '%s'",
				sourceDir.getAbsolutePath(), blog.getTitle()));

		LOG.info("Selfiebot(tm) open for business");

		while (true) {
			List<File> files = Arrays.asList(sourceDir
					.listFiles(new FileFilter() {

						public boolean accept(File file) {
							return file.isFile();
						}
					}));
			Collections.sort(files,
					LastModifiedFileComparator.LASTMODIFIED_COMPARATOR);

			if (!files.isEmpty()) {
				for (File file : files) {
					String filename = file.getName();
					InputStream in = null;

					try {
						File data = new File(sourceDir, filename);
						in = new FileInputStream(data);

						Image image = new Image();
						image.setData(in);
						image.setDescription(description);
						image.setTags(tags);
						image.setSource(source);

						LOG.info(String.format("Starting upload for file '%s'",
								filename));

						long tsStart = System.currentTimeMillis();
						PostResult postResult = soup.post(blog, image);

						LOG.info(String.format(
								"File '%s' uploaded, id=%d (in %d ms)",
								filename, postResult.getId(),
								System.currentTimeMillis() - tsStart));

						boolean moveOk = data.renameTo(new File(atticDir,
								filename));
						if (moveOk) {
							LOG.debug(String.format(
									"File '%s' moved into the attic", filename));
						} else {
							LOG.fatal(String.format("File '%s' failed to move",
									filename));
							LOG.fatal("Aborting!");
							return;
						}
					} catch (Exception exception) {
						LOG.error(String.format(
								"File '%s' failed to upload: %s", filename,
								exception.getMessage()), exception);
					} finally {
						IOUtils.closeQuietly(in);
					}
				}

				// don't wait, look for more files
				continue;
			}

			// wait a second before looking again
			Thread.sleep(1000);
		}

	}
}
