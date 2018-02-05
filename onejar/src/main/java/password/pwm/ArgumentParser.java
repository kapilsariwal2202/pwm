package password.pwm;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.ParseException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ArgumentParser
{

    public TomcatConfig parseArguments(final String[] args)
            throws ArgumentParserException, TomcatOneJarException
    {
        if (args == null || args.length == 0) {
            ArgumentParser.outputHelp();
        } else {
            final CommandLine commandLine;

            try {
                commandLine = new DefaultParser().parse( Argument.asOptions(), args );
            } catch ( ParseException e ) {
                throw new ArgumentParserException( "unable to parse command line: " + e.getMessage());
            }

            if ( commandLine.hasOption( Argument.version.name() ) ) {
                TomcatOneJarMain.out( TomcatOneJarMain.getVersion() );
                return null;
            } else if ( commandLine.hasOption( Argument.help.name() ) ) {
                ArgumentParser.outputHelp();
                return null;
            } else {
                final Map<Argument, String> argumentMap;
                if (commandLine.hasOption( Argument.properties.name())) {
                    if (args.length > 2) {
                        throw new ArgumentParserException( Argument.properties.name() + " must be the only argument specified" );
                    }
                    final String filename = commandLine.getOptionValue( Argument.properties.name() );
                    argumentMap = mapFromProperties( filename );
                } else {
                    argumentMap = mapFromCommandLine( commandLine );
                }
                final TomcatConfig tomcatConfig;
                try {
                    tomcatConfig = makeTomcatConfig( argumentMap );
                } catch ( IOException e ) {
                    throw new ArgumentParserException( "error while reading input: " + e.getMessage() );
                }
                return tomcatConfig;
            }
        }

        return null;
    }

    private Map<Argument,String> mapFromProperties(final String filename) throws ArgumentParserException
    {
        final Properties props = new Properties();
        try {
            props.load(new FileInputStream( new File( filename) ));
        } catch ( IOException e ) {
            throw new ArgumentParserException( "unable to read properties input file: " + e.getMessage());
        }

        final Map<Argument,String> map = new HashMap<>(  );
        for (final Option option : Argument.asOptionMap().values()) {
            if (option.hasArg()) {
                final Argument argument = Argument.valueOf( option.getOpt() );
                final String value = props.getProperty( argument.name() );
                if (value != null) {
                    map.put( argument, value );
                }
            }
        }
        return Collections.unmodifiableMap( map );
    }

    private Map<Argument,String> mapFromCommandLine(final CommandLine commandLine) {
        final Map<Argument,String> map = new HashMap<>(  );
        for (final Option option : Argument.asOptionMap().values()) {
            if (option.hasArg()) {
                if (commandLine.hasOption( option.getOpt() )) {
                    final Argument argument = Argument.valueOf( option.getOpt() );
                    final String value = commandLine.getOptionValue( option.getOpt() );
                    map.put( argument, value );
                }
            }
        }
        return Collections.unmodifiableMap(map);
    }


    private TomcatConfig makeTomcatConfig( final Map<Argument,String> argumentMap) throws IOException, ArgumentParserException
    {
        final TomcatConfig tomcatConfig = new TomcatConfig();
        tomcatConfig.setApplicationPath( parseFileOption( argumentMap, Argument.applicationPath ) );

        tomcatConfig.setContext( argumentMap.getOrDefault( Argument.context, Resource.defaultContext.getValue() ) );

        if (argumentMap.containsKey( Argument.war )) {
            final File inputWarFile = new File ( argumentMap.get( Argument.war ));
            if (!inputWarFile.exists()) {
                System.out.println( "output war file " + inputWarFile.getAbsolutePath() + "does not exist" );
                System.exit( -1 );
                return null;
            }
            tomcatConfig.setWar( new FileInputStream( inputWarFile ) );
        } else {
            tomcatConfig.setWar( getEmbeddedWar() );
        }

        tomcatConfig.setPort( Integer.parseInt( Resource.defaultPort.getValue() ) );
        if (argumentMap.containsKey( Argument.port )) {
            try {
                tomcatConfig.setPort( Integer.parseInt( argumentMap.get( Argument.port ) ) );
            } catch (NumberFormatException e) {
                System.out.println( Argument.port.name()  + " argument must be numeric" );
                System.exit( -1 );
            }
        }

        tomcatConfig.setLocalAddress( argumentMap.getOrDefault( Argument.localAddress, Resource.defaultLocalAddress.getValue() ) );

        try {
            final ServerSocket socket = new ServerSocket(tomcatConfig.getPort(), 100, InetAddress.getByName( tomcatConfig.getLocalAddress() ));
            socket.close();
        } catch(Exception e) {
            throw new ArgumentParserException( "port or address conflict: " + e.getMessage() );
        }

        if (argumentMap.containsKey( Argument.workPath )) {
            tomcatConfig.setWorkingPath( parseFileOption( argumentMap, Argument.workPath   ) );
        } else {
            tomcatConfig.setWorkingPath( figureDefaultWorkPath( tomcatConfig) );
        }

        return tomcatConfig;
    }



    private static void outputHelp() throws TomcatOneJarException
    {
        final HelpFormatter formatter = new HelpFormatter();
        System.out.println( TomcatOneJarMain.getVersion() );
        System.out.println( "usage:" );
        formatter.printOptions(
                System.console().writer(),
                HelpFormatter.DEFAULT_WIDTH,
                Argument.asOptions(),
                3 ,
                8);
    }


    private static File parseFileOption(final Map<Argument,String> argumentMap, final Argument argName) throws ArgumentParserException
    {
        if (!argumentMap.containsKey( argName )) {
            throw new ArgumentParserException( "option " + argName + " required");
        }
        final File file = new File(argumentMap.get( argName ));
        if (!file.isAbsolute()) {
            throw new ArgumentParserException( "a fully qualified file path name is required for " + argName);
        }
        if (!file.exists()) {
            throw new ArgumentParserException( "path specified by " + argName + " must exist");
        }
        return file;
    }

    private static File figureDefaultWorkPath(final TomcatConfig tomcatConfig) {
        final String userHomePath = System.getProperty( "user.home" );
        if (userHomePath != null && !userHomePath.isEmpty()) {
            final File basePath = new File(userHomePath + File.separator
                    + Resource.defaultWorkPathName.getValue());
            basePath.mkdir();
            final File workPath = new File( basePath.getPath() + File.separator
                    + "work"
                    + "-"
                    + escapeFilename( tomcatConfig.getContext() )
                    + "-"
                    + escapeFilename( Integer.toString( tomcatConfig.getPort() ) )
                    + "-"
                    + escapeFilename( tomcatConfig.getLocalAddress() )
            );
            workPath.mkdir();
            System.out.println( "using work directory: " + workPath.getAbsolutePath() );
            return workPath;
        }

        System.out.println( "cant locate user home directory" );
        System.exit( -1 );
        return null;
    }

    private static InputStream getEmbeddedWar() throws IOException, ArgumentParserException
    {
        final Class clazz = TomcatOneJarMain.class;
        final String className = clazz.getSimpleName() + ".class";
        final String classPath = clazz.getResource(className).toString();
        if (!classPath.startsWith("jar")) {
            throw new ArgumentParserException("not running from war, war option must be specified");
        }
        final String warPath = classPath.substring(0, classPath.lastIndexOf("!") + 1) +
                "/" + Resource.defaultWarFileName.getValue();
        return new URL(warPath).openStream();
    }

    private static String escapeFilename(final String input) {
        return input.replaceAll("\\W+", "_");
    }

}
