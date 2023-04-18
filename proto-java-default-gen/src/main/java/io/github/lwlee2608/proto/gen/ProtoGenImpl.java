package io.github.lwlee2608.proto.gen;

import io.github.lwlee2608.proto.annotation.exception.GeneratorException;
import io.github.lwlee2608.proto.annotation.processor.AsyncType;
import io.github.lwlee2608.proto.annotation.processor.Enumerated;
import io.github.lwlee2608.proto.annotation.processor.Field;
import io.github.lwlee2608.proto.annotation.processor.Message;
import io.github.lwlee2608.proto.annotation.processor.Method;
import io.github.lwlee2608.proto.annotation.processor.ProtoFile;
import io.github.lwlee2608.proto.annotation.processor.Service;
import lombok.SneakyThrows;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

import javax.annotation.processing.Filer;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.List;

public class ProtoGenImpl implements ProtoGen {
    private final CommandLineUtils.StringStreamConsumer error = new CommandLineUtils.StringStreamConsumer();
    private final CommandLineUtils.StringStreamConsumer output = new CommandLineUtils.StringStreamConsumer();

    @Override
    public void generate(Filer filer, List<ProtoFile> protoFiles) {
        protocGenerate(filer, protoFiles);
        generateDefaultImpl(filer, protoFiles);
    }

    @SneakyThrows
    public void protocGenerate(Filer filer, List<ProtoFile> protoFiles) {
        // Register file to be generated by protoc to 'filer'.
        // If we skip this steps, generated file will not be compiled for some reason
        for (ProtoFile protoFile : protoFiles) {
            if (protoFile.getOuterClassName() == null) {
                continue;
            }
            String fullOuterClassName = protoFile.getPackageName() + "." + protoFile.getOuterClassName();
            JavaFileObject builderFile = filer.createSourceFile(fullOuterClassName);
            PrintWriter out = new PrintWriter(builderFile.openWriter());
            out.close();
        }

        // Get output directory
        FileObject resource = filer.getResource(StandardLocation.SOURCE_OUTPUT, "", "Dummy.java");
        String outputDirectory = Paths.get(resource.toUri()).toFile().getParent();

        // Retrieve the protoc executable
        String protocExecutable = outputDirectory.substring(0, outputDirectory.indexOf("target")) + "target/protoc/bin/" + "protoc.exe";
        File protocExeFile = new File(protocExecutable);
        String executable = protocExeFile.exists() ? protocExecutable : "protoc";

        // Generate using protoc
        String protoPath = protoFiles.get(0).getGeneratedFile().getParent();
        String[] args = new String[]{
                "-I=.",
                "--java_out=" + outputDirectory,
                "--proto_path", protoPath};
        Commandline cl = new Commandline();
        cl.setExecutable(executable);
        cl.addArguments(args);
        protoFiles.forEach(protoFile -> cl.addArguments(new String[]{protoFile.getGeneratedFile().getAbsoluteFile().toString()}));

        int ret = CommandLineUtils.executeCommandLine(cl, null, output, error);
        if (ret != 0) {
            throw new GeneratorException("Protoc error: " + error.getOutput());
        }
    }

    @SneakyThrows
    public void generateDefaultImpl(Filer filer, List<ProtoFile> protoFiles) {
        for (ProtoFile protoFile: protoFiles) {
            String className = protoFile.getOuterClassName() + "Proto";
            String fullClassName = protoFile.getPackageName() + "." + className;
            //System.out.println("Proto class " + fullClassName);

            JavaFileObject builderFile = filer.createSourceFile(fullClassName);
            try (PrintWriter out = new PrintWriter(builderFile.openWriter())) {
                out.println("package " + protoFile.getPackageName() + ";");
                out.println("");
                out.println("import com.google.protobuf.*;");
                out.println("import java.util.concurrent.CompletableFuture;");
                out.println("import io.github.lwlee2608.proto.gen.util.CompletableFutureUtil;");
                out.println("import io.github.lwlee2608.proto.gen.util.StreamObserverUtil;");
                out.println("import io.grpc.CallOptions;");
                out.println("import io.grpc.Channel;");
                out.println("import io.grpc.MethodDescriptor;");
                out.println("import io.grpc.ServerServiceDefinition;");
                out.println("import io.grpc.ServiceDescriptor;");
                out.println("import io.grpc.protobuf.ProtoMethodDescriptorSupplier;");
                out.println("import io.grpc.protobuf.ProtoServiceDescriptorSupplier;");
                out.println("import io.grpc.protobuf.ProtoUtils;");
                out.println("import io.grpc.stub.ClientCalls;");
                out.println("import io.grpc.stub.ServerCalls;");
                out.println("import io.grpc.stub.StreamObserver;");
                out.println("import static io.grpc.MethodDescriptor.generateFullMethodName;");
                out.println("");
                out.println("public class " + className + " {");
                out.println("");

                if (protoFile.getEnums().size() > 0) {
                    out.println("    // Enum");
                }
                for (Enumerated enumerated : protoFile.getEnums()) {
                    String enumClassName = enumerated.getClassName();
                    String protoEnumClassName = protoFile.getOuterClassName() + "." + enumClassName;

                    out.println("    public static class " + enumClassName + "Enum {");
                    out.println("       public static " + protoEnumClassName + "Enum toProto(" + enumClassName + " pojo) {");
                    out.println("           return " + protoEnumClassName + "Enum.newBuilder()");
                    out.println("                   .setValue(" + protoEnumClassName + ".valueOf(pojo.value())).build();");
                    out.println("       }");
                    out.println("");
                    out.println("       public static " + enumClassName + " fromProto(" + protoEnumClassName + "Enum proto) {");
                    out.println("           return " + enumClassName + ".valueOf(proto.getValueValue());");
                    out.println("       }");
                    out.println("    }");
                    out.println("");
                }

                if (protoFile.getMessages().size() > 0) {
                    out.println("    // Messages");
                }
                for (Message message : protoFile.getMessages()) {
                    String messageClassName = message.getClassName();
                    String protoMessageClassName = protoFile.getOuterClassName() + "." + messageClassName;
                    out.println("    public static class " + messageClassName + "Message {");
                    out.println("        public static " + protoMessageClassName + " toProto(" + messageClassName + " pojo) {");
                    out.println("            " + protoMessageClassName + ".Builder builder = " + protoMessageClassName + ".newBuilder();");
                    for (Field field : message.getFields()) {
                        String setter = getSetter(field.getName());
                        String getter = getGetter(field.getName());
                        if (field.getIsStruct()) {
                            String messageType = getSimpleClass(field.getJavaType()) + "Message";
                            out.println("            if (pojo." + getter +"() != null) {");
                            out.println("                builder." + setter + "(" + messageType + ".toProto(pojo." + getter + "()));");
                            out.println("            }");
                        } else if (field.getIsEnum()) {
                            String enumType = field.getProtoType();
                            out.println("            if (pojo." + getter + "() != null) {");
                            out.println("                builder." + setter + "(" + enumType + ".toProto(pojo." + getter + "()));");
                            out.println("            }");
                        } else {
                            String wrapperFunction = getSimpleClass(field.getProtoType()) + ".of";
                            out.println("            if (pojo." + getter +"() != null) {");
                            out.println("                builder." + setter + "(" + wrapperFunction + "(pojo." + getter + "()));");
                            out.println("            }");
                        }
                    }
                    out.println("            return builder.build();");
                    out.println("        }");
                    out.println("");
                    out.println("        public static " + messageClassName + " fromProto(" + protoMessageClassName + " proto) {");
                    out.println("            " + messageClassName + " pojo = new " + messageClassName + "();");
                    for (Field field : message.getFields()) {
                        String setter = getSetter(field.getName());
                        String getter = getGetter(field.getName());
                        String hasFunction = getHasFunction(field.getName());
                        if (field.getIsStruct()) {
                            String messageType = getSimpleClass(field.getJavaType()) + "Message";
                            out.println("            if (proto." + hasFunction +"()) {");
                            out.println("                pojo." + setter + "(" + messageType + ".fromProto(proto." + getter + "()));");
                            out.println("            }");
                        } else if (field.getIsEnum()) {
                            String enumType = field.getProtoType();
                            out.println("            if (proto." + hasFunction +"()) {");
                            out.println("                pojo." + setter + "(" + enumType + ".fromProto(proto." + getter + "()));");
                            out.println("            }");
                        } else {
                            out.println("            if (proto." + hasFunction +"()) {");
                            out.println("                pojo." + setter + "(proto." + getter + "().getValue());");
                            out.println("            }");
                        }
                    }
                    out.println("            return pojo;");
                    out.println("        }");
                    out.println("    }");
                    out.println("");
                }
                if (protoFile.getServices().size() > 0) {
                    out.println("    // Services");
                }
                for (Service service : protoFile.getServices()) {
                    out.println("    public static class " + service.getServiceName() + "Service {");
                    out.println("        public static final String SERVICE_NAME = \"" + service.getFullServiceName() + "\";");
                    out.println("");
                    out.println("        // Client");
                    out.println("        public static class " + service.getServiceName() + "ClientImpl implements " + service.getServiceName() + " {");
                    out.println("            private final Channel channel;");
                    out.println("            private final CallOptions callOptions;");
                    out.println("");
                    out.println("            public " + service.getServiceName() + "ClientImpl(Channel channel, CallOptions callOptions) {");
                    out.println("                this.channel = channel;");
                    out.println("                this.callOptions = callOptions;");
                    out.println("            }");
                    out.println("");
                    for (Method method: service.getMethods()) {
                        String inputType = method.getInputType().getClassName();
                        String outputType = method.getOutputType().getClassName();
                        out.println("            @Override");
                        if (method.getAsyncType() == AsyncType.STREAM_OBSERVER) {
                            out.println("            public void " + method.getMethodName() + "(" + inputType + " request, StreamObserver<" + outputType + "> streamObserver) {");
                            out.println("                ClientCalls.asyncUnaryCall(channel.newCall(" + method.getMethodName() + "Method, callOptions),");
                            out.println("                        " + className + "." + inputType + "Message.toProto(request),");
                            out.println("                        StreamObserverUtil.transform(streamObserver, " + className + "." + outputType + "Message::fromProto));");
                        } else if (method.getAsyncType() == AsyncType.COMPLETABLE_FUTURE) {
                            out.println("            public CompletableFuture<" + outputType + "> " + method.getMethodName() + "(" + inputType + " request) {");
                            out.println("                CompletableFuture<" + outputType + "> future = new CompletableFuture<>();");
                            out.println("                ClientCalls.asyncUnaryCall(channel.newCall(" + method.getMethodName() + "Method, callOptions),");
                            out.println("                        " + className + "." + inputType + "Message.toProto(request),");
                            out.println("                        CompletableFutureUtil.fromStreamObserver(future, " + className + "." + outputType + "Message::fromProto));");
                            out.println("                return future;");
                        }
                        out.println("            }");
                    }
                    out.println("        }");
                    out.println("");
                    out.println("        // Server");
                    out.println("        public static class " + service.getServiceName() + "ServerImpl implements io.grpc.BindableService {");
                    out.println("            private final " + service.getServiceName() + " impl;");
                    out.println("");
                    out.println("            public " + service.getServiceName() + "ServerImpl(" + service.getServiceName() + " impl) {");
                    out.println("                this.impl = impl;");
                    out.println("            }");
                    out.println("");
                    out.println("            @Override");
                    out.println("            public ServerServiceDefinition bindService() {");
                    out.println("                return io.grpc.ServerServiceDefinition.builder(serviceDescriptor)");
                    for (Method method: service.getMethods()) {
                        String inputType = method.getInputType().getClassName();
                        String outputType = method.getOutputType().getClassName();
                        String protoInput = protoFile.getOuterClassName() + "." + inputType;
                        String protoOutput = protoFile.getOuterClassName() + "." + outputType;
                        out.println("                        .addMethod(" + method.getMethodName() + "Method, ServerCalls.asyncUnaryCall(new ServerCalls.UnaryMethod<" + protoInput + ", " + protoOutput + ">() {");
                        out.println("                            @Override");
                        if (method.getAsyncType() == AsyncType.STREAM_OBSERVER) {
                            out.println("                            public void invoke(" + protoInput + " request, StreamObserver<" + protoOutput + "> streamObserver) {");
                            out.println("                                impl.sayHello(" + className + "." + inputType + "Message.fromProto(request),");
                            out.println("                                        StreamObserverUtil.transform(streamObserver, " + className + "." + outputType + "Message::toProto));");
                        } else if (method.getAsyncType() == AsyncType.COMPLETABLE_FUTURE) {
                            out.println("                            public void invoke(" + protoInput + " request, StreamObserver<" + protoOutput + "> streamObserver) {");
                            out.println("                                CompletableFuture<" + outputType + "> future = impl." + method.getMethodName() + "(" + protoFile.getOuterClassName() + "Proto." + inputType + "Message.fromProto(request));");
                            out.println("                                CompletableFutureUtil.toStreamObserver(future, streamObserver, " + protoFile.getOuterClassName() + "Proto." + outputType + "Message::toProto);");
                        }
                        out.println("                            }");
                        out.println("                        }))");
                    }
                    out.println("                        .build();");
                    out.println("            }");
                    out.println("        }");
                    out.println("");
                    out.println("        // Method Descriptors");
                    for (Method method: service.getMethods()) {
                        String protoInput = protoFile.getOuterClassName() + "." + method.getInputType().getClassName();
                        String protoOutput = protoFile.getOuterClassName() + "." + method.getOutputType().getClassName();
                        out.println("        public static final MethodDescriptor<" + protoInput + ", " + protoOutput + "> " + method.getMethodName() + "Method");
                        out.println("                = MethodDescriptor.<" + protoInput + ", " + protoOutput + ">newBuilder()");
                        out.println("                .setType(MethodDescriptor.MethodType.UNARY)");
                        out.println("                .setFullMethodName(generateFullMethodName(SERVICE_NAME, \"" + method.getMethodName() + "\"))");
                        out.println("                .setSampledToLocalTracing(true)");
                        out.println("                .setRequestMarshaller(ProtoUtils.marshaller(" + protoInput + ".getDefaultInstance()))");
                        out.println("                .setResponseMarshaller(ProtoUtils.marshaller(" + protoOutput + ".getDefaultInstance()))");
                        out.println("                .setSchemaDescriptor(new ProtoMethodDescriptorSupplier() {");
                        out.println("                    @Override public Descriptors.ServiceDescriptor getServiceDescriptor() { return getFileDescriptor().findServiceByName(SERVICE_NAME);}");
                        out.println("                    @Override public Descriptors.MethodDescriptor getMethodDescriptor() { return getServiceDescriptor().findMethodByName(\"" + method.getMethodName() + "\"); }");
                        out.println("                    @Override public Descriptors.FileDescriptor getFileDescriptor() { return " + protoFile.getOuterClassName() + ".getDescriptor(); }");
                        out.println("                })");
                        out.println("                .build();");
                        out.println("");
                    }
                    out.println("        // Service Descriptors");
                    out.println("        public static final ServiceDescriptor serviceDescriptor = ServiceDescriptor.newBuilder(SERVICE_NAME)");
                    out.println("                .setSchemaDescriptor(new ProtoServiceDescriptorSupplier() {");
                    out.println("                    @Override public Descriptors.FileDescriptor getFileDescriptor() { return " + protoFile.getOuterClassName() + ".getDescriptor(); }");
                    out.println("                    @Override public Descriptors.ServiceDescriptor getServiceDescriptor() { return getFileDescriptor().findServiceByName(SERVICE_NAME); }");
                    out.println("                })");
                    for (Method method: service.getMethods()) {
                        out.println("                .addMethod(" + method.getMethodName() + "Method)");
                    }
                    out.println("                .build();");
                    out.println("    }");
                    out.println("");
                }
                out.println("}");
                out.println("");
            }
        }
    }

    private String getSetter(String fieldName) {
        String name = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
        return "set" + name;
    }

    private String getGetter(String fieldName) {
        String name = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
        return "get" + name;
    }

    private String getHasFunction(String fieldName) {
        String name = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
        return "has" + name;
    }

    private String getSimpleClass(String fullyQualifiedClassName) {
        String[] split = fullyQualifiedClassName.split("\\.");
        return split[split.length - 1];
    }
}
