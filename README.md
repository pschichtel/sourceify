# sourceify

A tool to decompile an entire maven repository to provide source jars. The primary use-case is to to provide `-source.jar`'s for external non-opensource dependencies in order to improve the debugging experience in IDEs.

## Usage

```
java -jar sourceify.jar /path/to/maven/repository ./fernflower.sh
```
