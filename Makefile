dist:
	sh ./src/scripts/dist.sh

channel:
	./sc4pac build-channel ./channel
channel-testing:
	./sc4pac build-channel ./channel-testing

host:
	# jwebserver comes with java 18
	cd channel-testing/json/ && jwebserver -p 8090 -o info

# sbt:
# 	sbt -Dcoursier.credentials="$(realpath sc4pac-credentials.properties)"

clean:
	rm -rf plugins temp sc4pac-plugins.json sc4pac-plugins-lock.json
clean-cache: clean
	rm -rf cache

test:
	./sc4pac update
	sed -i 's#"https.*raw.githubusercontent.*"#"http://localhost:8090"#g' sc4pac-plugins.json
	./sc4pac add memo:demo-package
	./sc4pac update

# conversion from asciinema asciicast to gif using https://github.com/asciinema/agg
demo-video.gif: demo-video.cast
	agg --speed 2 --cols 80 --last-frame-duration 8 --theme asciinema demo-video.cast demo-video.gif


.PHONY: dist channel channel-testing host clean clean-cache test
