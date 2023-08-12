plugins {
    id("java")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    implementation("org.jacodb:jacodb-api:1.2.0")
    implementation("org.jacodb:jacodb-core:1.2.0")
    implementation("org.jacodb:jacodb-analysis:1.2.0")
    implementation("org.jacodb:jacodb-approximations:1.2.0")
}

tasks.test {
    useJUnitPlatform()
}