dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:${rootProject.property("junitVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
