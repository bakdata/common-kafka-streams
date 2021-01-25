[![Build Status](https://dev.azure.com/bakdata/public/_apis/build/status/bakdata.streams-bootstrap?branchName=master)](https://dev.azure.com/bakdata/public/_build/latest?definitionId=5&branchName=master)
[![Sonarcloud status](https://sonarcloud.io/api/project_badges/measure?project=com.bakdata.kafka%3Astreams-bootstrap&metric=alert_status)](https://sonarcloud.io/dashboard?id=com.bakdata.kafka%3Astreams-bootstrap)
[![Code coverage](https://sonarcloud.io/api/project_badges/measure?project=com.bakdata.kafka%3Astreams-bootstrap&metric=coverage)](https://sonarcloud.io/dashboard?id=com.bakdata.kafka%3Astreams-bootstrap)
[![Maven](https://img.shields.io/maven-central/v/com.bakdata.kafka/streams-bootstrap.svg)](https://search.maven.org/search?q=g:com.bakdata.kafka%20AND%20a:streams-bootstrap&core=gav)


# streams-bootstrap

## Getting Started

You can add streams-bootstrap via Maven Central.

#### Gradle
```gradle
compile group: 'com.bakdata.kafka', name: 'streams-bootstrap', version: '1.6.0'
```

#### Maven
```xml
<dependency>
    <groupId>com.bakdata.kafka</groupId>
    <artifactId>streams-bootstrap</artifactId>
    <version>1.6.0</version>
</dependency>
```


For other build tools or versions, refer to the [latest version in MvnRepository](https://mvnrepository.com/artifact/com.bakdata.kafka/streams-bootstrap/latest).

## Development

If you want to contribute to this project, you can simply clone the repository and build it via Gradle.
All dependencies should be included in the Gradle files, there are no external prerequisites.

```bash
> git clone git@github.com:bakdata/streams-bootstrap.git
> cd streams-bootstrap && ./gradlew build
```

Please note, that we have [code styles](https://github.com/bakdata/bakdata-code-styles) for Java.
They are basically the Google style guide, with some small modifications.

## Contributing

We are happy if you want to contribute to this project.
If you find any bugs or have suggestions for improvements, please open an issue.
We are also happy to accept your PRs.
Just open an issue beforehand and let us know what you want to do and why.

## License
This project is licensed under the MIT license.
Have a look at the [LICENSE](https://github.com/bakdata/streams-bootstrap/blob/master/LICENSE) for more details.
