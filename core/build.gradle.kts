dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:${rootProject.property("junitVersion")}")
}

tasks.test {
    useJUnitPlatform()
}
