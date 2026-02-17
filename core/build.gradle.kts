dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:${rootProject.property("junitVersion")}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${rootProject.property("junitVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
