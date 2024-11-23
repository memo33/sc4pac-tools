dist:
	sh ./src/scripts/dist.sh

channel-testing:
	./sc4pac channel build --label Test-Local --output ./channel-testing/json ./channel-testing/yaml
channel-testing-web:
	./sc4pac channel build --label Test-Web --metadata-source-url https://github.com/memo33/sc4pac/blob/main/src/yaml/ --output ./web/channel/ ./channel-testing/yaml

host:
	# jwebserver comes with java 18
	cd channel-testing/json/ && jwebserver -p 8090 -o info
host-web: # channel-testing-web
	# python has some support for symlinks
	cd web/ && python -m http.server 8090

# sbt:
# 	sbt -Dcoursier.credentials="$(realpath sc4pac-credentials.properties)"

clean:
	rm -rf plugins temp sc4pac-plugins.json sc4pac-plugins-lock.json profiles
clean-cache: clean
	rm -rf cache

test:
	./sc4pac channel add "http://localhost:8090"
	./sc4pac add memo:demo-package
	./sc4pac update
test-sbt:
	sbt test

# conversion from asciinema asciicast to gif using https://github.com/asciinema/agg
demo-video.gif: demo-video.cast
	agg --speed 2 --cols 80 --last-frame-duration 8 --theme asciinema demo-video.cast demo-video.gif


.PHONY: dist channel-testing channel-testing-web host host-web clean clean-cache test test-sbt
