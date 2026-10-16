// Browser build only: compiles the game sources plus web/src (WebMain, which needs the TeaVM JSO
// classes) and translates wawa.WebMain to JavaScript. The desktop build stays plain javac over src/.
plugins {
    java
    id("org.teavm") version "0.14.1"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(teavm.libs.jsoApis)
}

sourceSets {
    main {
        java.srcDir("web/src")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 17
}

teavm {
    js {
        mainClass.set("wawa.WebMain")
    }
}
