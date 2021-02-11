package com.vimasig.bozar.obfuscator;

import com.vimasig.bozar.obfuscator.transformer.TransformManager;
import com.vimasig.bozar.obfuscator.utils.StreamUtils;
import com.vimasig.bozar.obfuscator.utils.model.BozarConfig;
import com.vimasig.bozar.obfuscator.utils.model.BozarMessage;
import com.vimasig.bozar.obfuscator.utils.model.CustomClassWriter;
import com.vimasig.bozar.obfuscator.utils.model.ResourceWrapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Bozar implements Runnable {

    private final File input;
    private final Path output;
    private final BozarConfig config;

    public Bozar(File input, Path output, BozarConfig config) {
        this.input = input;
        this.output = output;
        this.config = config;
    }

    private final List<ClassNode> classes = new ArrayList<>();
    private final List<ResourceWrapper> resources = new ArrayList<>();

    @Override
    public void run() {
        try {
            // Input file checks
            if(!this.input.exists())
                throw new FileNotFoundException("Cannot find input");
            if(!this.input.isFile())
                throw new IllegalArgumentException("Received input is not a file");
            String inputExtension = this.input.getName().substring(this.input.getName().lastIndexOf(".") + 1).toLowerCase();
            switch (inputExtension) {
                case "jar" -> {
                    // Read JAR input
                    log("Processing JAR input...");
                    try (var jarInputStream = new ZipInputStream(Files.newInputStream(input.toPath()))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = jarInputStream.getNextEntry()) != null) {
                            if (zipEntry.getName().endsWith(".class")) {
                                ClassReader reader = new ClassReader(jarInputStream);
                                ClassNode classNode = new ClassNode();
                                reader.accept(classNode, 0);
                                classes.add(classNode);
                            } else resources.add(new ResourceWrapper(zipEntry, StreamUtils.readAll(jarInputStream)));
                        }
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported file extension: " + inputExtension);
            }
            
            // Empty/corrupted file check
            if(classes.size() == 0)
                throw new IllegalArgumentException("Received input does not look like a proper JAR file");

            // Transform
            log("Transforming...");
            var transformHandler = new TransformManager(this);
            transformHandler.transform();

            // Write output
            log("Writing...");
            try (var out = new JarOutputStream(Files.newOutputStream(this.output))) {
                // Write resources
                resources.forEach(resourceWrapper -> {
                    try {
                        out.putNextEntry(new JarEntry(resourceWrapper.getZipEntry().getName()));
                        StreamUtils.copy(new ByteArrayInputStream(resourceWrapper.getBytes()), out);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });

                // Convert string library paths to URL array
                var libs = this.getConfig().getLibraries();
                URL[] urls = new URL[libs.size() + 1];
                urls[libs.size()] = this.input.toURI().toURL();
                for (int i = 0; i < libs.size(); i++)
                    urls[i] = new File(libs.get(i)).toURI().toURL();
                URLClassLoader classLoader = new URLClassLoader(urls);

                // Write classes
                for(ClassNode classNode : this.classes) {
                    var classWriter = new CustomClassWriter(ClassWriter.COMPUTE_FRAMES, classLoader);
                    classWriter.newUTF8(BozarMessage.WATERMARK.toString());
                    classNode.accept(classWriter);
                    byte[] bytes = classWriter.toByteArray();
                    out.putNextEntry(new JarEntry(classNode.name + ".class"));
                    out.write(bytes);
                }
            }
            // TODO: Print elapsed time & file bloat
            log("Done.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public List<ClassNode> getClasses() {
        return classes;
    }

    public List<ResourceWrapper> getResources() {
        return resources;
    }

    public BozarConfig getConfig() {
        return config;
    }

    public void log(String format, Object... args) {
        System.out.println("[Bozar] " + String.format(format, args));
    }

    public void err(String format, Object... args) {
        System.out.println("[Bozar] [ERROR] " + String.format(format, args));
    }
}