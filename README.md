## LANragagi Fakku Tagger
A quick and dirty program that connects to your LANraragi instance, and re-tags all galleries using different metadata grabbers.

The process can be simplified like this:
`koromo -> fakku -> chaika`

Additional details on the process:
- It checks for a Koromo metadata file
  - If it finds one it resets tags for that archive, and uses Koromo to re-tag the archive, and uses the Fakku link for additional tags.
- It searches for a Fakku link directly on Fakku, or through `chaika.moe` if one wasnt provided by Koromo.
- If it finds multiple results, it will choose the one that directly matches the title, or it will ask the user to choose the correct one if possible.
- If the Fakku gallery is found on `chaika.moe` and is unavailable on Fakku, it will get the tags from the `chaika.moe` page.

### How to run the program
It uses Java so make sure you have that installed

Example command:
```
java -jar LanraragiFakkuTagger-1.3.jar <lanraragi_api_key> <lanraragi link, something like http://192.168.0.5> <fakku_sid_cookie> <amount to process, 0 for all> <offset to start with, defaults to 0>
```
Additional modifiers can be added, such as `debug` for debug output, or `onlyUntagged` to only try to tag untagged galleries.