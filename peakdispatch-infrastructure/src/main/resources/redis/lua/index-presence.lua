local generation = tonumber(ARGV[1])
local sequence = tonumber(ARGV[2])
local member = ARGV[3]
local old = redis.call('HGET', KEYS[2], member)
if old then
    local oldGeneration, oldSequence = string.match(old, '^(%d+):(%d+)$')
    oldGeneration = tonumber(oldGeneration)
    oldSequence = tonumber(oldSequence)
    if oldGeneration > generation or (oldGeneration == generation and oldSequence > sequence) then
        return 0
    end
end
redis.call('GEOADD', KEYS[1], ARGV[4], ARGV[5], member)
redis.call('HSET', KEYS[2], member, generation .. ':' .. sequence)
redis.call('ZADD', KEYS[3], ARGV[6], member)
return 1
